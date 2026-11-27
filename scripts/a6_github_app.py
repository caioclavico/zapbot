"""Credencial efêmera por chamada; recovery não depende do processo da janela."""
from __future__ import annotations

import base64
from datetime import datetime
import json
import os
import ssl
import subprocess
import time
import urllib.error
import urllib.request

from a6_production_deploy import DeployError

APP_ID = 5946171
INSTALLATION_ID = 186342817
REPO = "caioclavico/zapbot"
SERVICE_ACCOUNT = "320125207473-compute@developer.gserviceaccount.com"
SECRET_VERSION = "projects/320125207473/secrets/odisseu-github-app-private-key/versions/1"
METADATA = "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/"
GITHUB = "https://api.github.com"
READ = ["gh", "api", "repos/" + REPO + "/actions/variables/ODISSEU_PRODUCTION_DEPLOY_HOLD",
        "--jq", ".value"]
WRITE = ["gh", "variable", "set", "ODISSEU_PRODUCTION_DEPLOY_HOLD", "--repo", REPO, "--body"]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request(url, headers, payload=None):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.load_default_certs()
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), NoRedirect(),
        urllib.request.HTTPSHandler(context=context))
    data = None if payload is None else json.dumps(payload).encode()
    request_headers = dict(headers)
    if data is not None:
        request_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=request_headers)
    try:
        with opener.open(req, timeout=30) as response:
            raw = response.read(65537)
            if len(raw) > 65536:
                raise DeployError("github_app_response_too_large")
            return response.status, raw
    except urllib.error.HTTPError as exc:
        # Nenhum corpo remoto ou cabeçalho de autenticação sai do broker.
        return exc.code, b""
    except (OSError, urllib.error.URLError, TimeoutError) as exc:
        raise DeployError("github_app_transport_failed") from exc


def document(raw):
    try:
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (ValueError, TypeError) as exc:
        raise DeployError("github_app_response_invalid") from exc


def encode(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=")


def sign(body, key):
    if not 1 <= len(key) <= 4096:
        raise DeployError("github_app_key_invalid")
    read_fd, write_fd = os.pipe()
    try:
        if os.write(write_fd, key) != len(key):
            raise DeployError("github_app_key_pipe_failed")
        os.close(write_fd)
        write_fd = None
        completed = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", "/dev/fd/" + str(read_fd)],
            input=body, capture_output=True, pass_fds=(read_fd,), timeout=15)
        if completed.returncode:
            raise DeployError("github_app_signing_failed")
        return completed.stdout
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise DeployError("github_app_signing_failed") from exc
    finally:
        os.close(read_fd)
        if write_fd is not None:
            os.close(write_fd)


class AppBroker:
    """Sem cache em disco ou token herdado: renova em toda chamada de gh."""

    def __init__(self, http=request, signer=sign, clock=time.time):
        self.http, self.signer, self.clock = http, signer, clock

    def _json(self, url, headers, expected=200, payload=None):
        status, raw = self.http(url, headers, payload)
        if status != expected:
            raise DeployError("github_app_http_" + str(status))
        return document(raw)

    def token(self):
        metadata_headers = {"Metadata-Flavor": "Google"}
        status, raw = self.http(METADATA + "email", metadata_headers)
        if status != 200 or raw.strip() != SERVICE_ACCOUNT.encode():
            raise DeployError("github_app_vm_identity_mismatch")
        status, raw = self.http(METADATA + "scopes", metadata_headers)
        if status != 200 or b"https://www.googleapis.com/auth/cloud-platform" not in raw.splitlines():
            raise DeployError("github_app_oauth_scope_missing")
        oauth = self._json(METADATA + "token", metadata_headers)
        access = oauth.get("access_token")
        if (not isinstance(access, str) or not access or "\n" in access or "\r" in access
                or type(oauth.get("expires_in")) is not int or oauth["expires_in"] < 60):
            raise DeployError("github_app_metadata_token_invalid")
        secret = self._json("https://secretmanager.googleapis.com/v1/" + SECRET_VERSION + ":access",
                            {"Authorization": "Bearer " + access})
        try:
            if secret.get("name") != SECRET_VERSION:
                raise ValueError()
            key = base64.b64decode(secret["payload"]["data"], validate=True)
        except (ValueError, KeyError, TypeError) as exc:
            raise DeployError("github_app_secret_invalid") from exc
        now = int(self.clock())
        body = encode(b'{"alg":"RS256","typ":"JWT"}') + b"." + encode(json.dumps(
            {"iat": now - 60, "exp": now + 480, "iss": str(APP_ID)},
            separators=(",", ":")).encode())
        try:
            signature = self.signer(body, key)
        except DeployError:
            raise
        except OSError as exc:
            raise DeployError("github_app_signing_failed") from exc
        jwt = (body + b"." + encode(signature)).decode()
        headers = {"Authorization": "Bearer " + jwt, "Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "odisseu-a6"}
        installation = self._json(GITHUB + "/repos/" + REPO + "/installation", headers)
        if (installation.get("id") != INSTALLATION_ID or installation.get("app_id") != APP_ID
                or not isinstance(installation.get("account"), dict)
                or installation["account"].get("login") != "caioclavico"
                or installation.get("suspended_at") is not None
                or installation.get("repository_selection") != "selected"
                or installation.get("permissions") != {"actions_variables": "write", "metadata": "read"}):
            raise DeployError("github_app_installation_mismatch")
        token = self._json(GITHUB + "/app/installations/" + str(INSTALLATION_ID) + "/access_tokens",
                           headers, 201, {"repositories": ["zapbot"],
                                          "permissions": {"actions_variables": "write"}})
        try:
            expires = datetime.fromisoformat(token["expires_at"].replace("Z", "+00:00"))
            if expires.tzinfo is None:
                raise ValueError()
            ttl = expires.timestamp() - self.clock()
            credential = token["token"]
            if (not isinstance(credential, str) or not credential or "\n" in credential
                    or "\r" in credential or not 300 <= ttl <= 3660
                    or token.get("permissions") != {"actions_variables": "write", "metadata": "read"}
                    or token.get("repository_selection") != "selected"):
                raise ValueError()
        except (ValueError, KeyError, TypeError) as exc:
            raise DeployError("github_app_token_invalid") from exc
        repositories = self._json(GITHUB + "/installation/repositories",
                                  dict(headers, Authorization="Bearer " + credential))
        listed = repositories.get("repositories")
        if (repositories.get("total_count") != 1 or not isinstance(listed, list)
                or len(listed) != 1 or not isinstance(listed[0], dict)
                or listed[0].get("full_name") != REPO):
            raise DeployError("github_app_token_repository_mismatch")
        return credential


class AppGhRunner:
    def __init__(self, broker=None, process=subprocess.run):
        self.broker, self.process = broker or AppBroker(), process

    def __call__(self, args):
        if args != READ and not (len(args) == len(WRITE) + 1 and args[:-1] == WRITE
                                 and args[-1] in ("true", "false")):
            raise DeployError("github_app_command_refused")
        for attempt in range(2):
            token = self.broker.token()
            env = {k: v for k, v in os.environ.items()
                   if not k.startswith(("GH_", "GITHUB_", "GIT_TRACE", "GIT_CURL"))
                   and k not in ("HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY",
                                 "https_proxy", "http_proxy", "all_proxy",
                                 "SSLKEYLOGFILE")}
            env.update(GH_TOKEN=token, GH_HOST="github.com",
                       GH_PROMPT_DISABLED="1", GH_PAGER="cat", NO_PROXY="*")
            try:
                result = self.process(args, capture_output=True, text=True, timeout=45, env=env)
            except (OSError, subprocess.TimeoutExpired) as exc:
                raise DeployError("github_app_gh_failed") from exc
            finally:
                env.pop("GH_TOKEN", None)
                del token
            if result.returncode == 0:
                if args == READ and result.stdout.strip() not in ("true", "false"):
                    raise DeployError("hold_indeterminate")
                return subprocess.CompletedProcess(args, 0, result.stdout if args == READ else "", "")
            if attempt == 0 and "HTTP 401" in (result.stderr or ""):
                continue
            raise DeployError("github_app_gh_failed")
        raise DeployError("github_app_gh_failed")
