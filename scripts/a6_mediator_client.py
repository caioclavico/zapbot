"""Client for the A6 Docker mediator.  It maps no arbitrary Docker syntax."""
from __future__ import annotations

import json
import subprocess

from a6_production_deploy import DeployError

MEDIATOR = "/usr/local/libexec/odisseu-a6/a6-docker-mediator"


class MediatorRunner:
    def __init__(self, mediator=MEDIATOR, runner=subprocess.run):
        self.mediator = mediator
        self.runner = runner

    def __call__(self, request):
        completed = self.runner(["sudo", "-n", self.mediator], input=json.dumps(request),
                                text=True, capture_output=True, check=False)
        if completed.returncode:
            return completed
        try:
            reply = json.loads(completed.stdout)
        except ValueError as error:
            raise DeployError("mediator_reply_invalid") from error
        return subprocess.CompletedProcess(request, 0 if reply.get("ok") else 2,
                                           reply.get("stdout", ""), reply.get("stderr", ""))
