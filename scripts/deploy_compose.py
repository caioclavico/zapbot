"""Odisseu's Compose adapter. Output may contain Env: never relay it to SSH logs.

A distinct project per transaction prevents Compose from deleting a renamed,
retained container. Networks remain external; only the bot service is created.
"""
import copy
import json
import os
from pathlib import Path
import subprocess


class ComposeError(Exception):
    pass


class ProductionCompose:
    def __init__(self, app_dir, state_dir, write_private, runner=subprocess.run):
        self.app_dir, self.state_dir = Path(app_dir), Path(state_dir)
        self.write, self.runner = write_private, runner
        self.plan = self.project = None

    def invoke(self, files, operation, *, image='', project='zapbot-validation'):
        command = ['docker', 'compose', '--project-directory', str(self.app_dir),
                   '--env-file', '/dev/null', '--project-name', project]
        for file in files:
            command.extend(['--file', str(file)])
        command.extend(operation)
        try:
            result = self.runner(command, cwd=self.app_dir, stdin=subprocess.DEVNULL,
                capture_output=True, text=True, timeout=120,
                env={'PATH': os.environ.get('PATH', '/usr/bin:/bin'), 'LANG': 'C.UTF-8',
                     'ZAPBOT_APP_DIR': str(self.app_dir), 'ZAPBOT_IMAGE': image})
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ComposeError('Compose unavailable or operation timed out; inspect private transaction') from error
        if result.returncode:
            raise ComposeError('Compose operation failed; response withheld to protect environment')
        return result.stdout

    def prepare(self, transaction, name, original, configuration):
        source = self.app_dir / 'docker-compose.production.yml'
        if source.is_symlink() or not source.is_file():
            raise ComposeError('A regular docker-compose.production.yml is required on the Google VM')
        try:
            model = json.loads(self.invoke([source], ['config', '--format', 'json'],
                                          image=configuration['Image']))
        except (ValueError, TypeError) as error:
            raise ComposeError('Production Compose configuration is invalid') from error
        if set(model.get('services', {})) != {'bot'}:
            raise ComposeError('Production Compose must contain only the bot service')
        service = model['services']['bot']
        if any(service.get(k) for k in ('build', 'depends_on', 'volumes_from', 'configs', 'secrets',
                                       'develop', 'extends', 'post_start', 'pre_stop', 'provider')):
            raise ComposeError('Production Compose has unsupported dependencies or lifecycle hooks')
        if service.get('container_name') != name:
            raise ComposeError('Production Compose container name differs from the production container')
        if service.get('command') is not None and service['command'] != original['Config'].get('Cmd'):
            raise ComposeError('Production Compose command differs from the running container; operator review required')
        if any(model.get(k) for k in ('volumes', 'configs', 'secrets')):
            raise ComposeError('Production Compose must not create persistent resources')
        mounts = service.get('volumes') or []
        expected = {(m['Source'], m['Destination'], not m.get('RW'))
                    for m in original.get('Mounts', []) if m['Type'] == 'bind'}
        if any(m.get('type') != 'bind' for m in mounts) or {
                (m.get('source'), m.get('target'), bool(m.get('read_only')))
                for m in mounts} != expected:
            raise ComposeError('Production Compose persistence mounts differ from the existing container')
        # The existing effective Env is authoritative, including the host tunnel
        # and Pokemon URL. Reading the VM .env never overwrites it.
        config, host = configuration, configuration['HostConfig']
        service.update(image=config['Image'], pull_policy='never', hostname=config['Hostname'],
                       environment=dict(e.split('=', 1) for e in config.get('Env', []) if '=' in e),
                       command=config.get('Cmd'), entrypoint=config.get('Entrypoint'),
                       user=config.get('User', ''), working_dir=config.get('WorkingDir', ''),
                       labels={k: v for k, v in (config.get('Labels') or {}).items()
                               if not k.startswith('com.docker.compose.')},
                       stop_grace_period=str(config['StopTimeout']) + 's')
        service.pop('env_file', None)  # Resolved privately; do not re-read it during replacement.
        for setting, field in [('mem_limit', 'Memory'), ('memswap_limit', 'MemorySwap'),
                               ('shm_size', 'ShmSize'), ('cpu_shares', 'CpuShares'),
                               ('cpu_period', 'CpuPeriod'), ('cpu_quota', 'CpuQuota'),
                               ('cpuset', 'CpusetCpus'), ('pids_limit', 'PidsLimit')]:
            if host.get(field):
                service[setting] = host[field]
        for setting, field in [('cap_add', 'CapAdd'), ('cap_drop', 'CapDrop'),
                               ('security_opt', 'SecurityOpt'), ('dns', 'Dns'),
                               ('dns_search', 'DnsSearch'), ('dns_opt', 'DnsOptions'),
                               ('group_add', 'GroupAdd'), ('extra_hosts', 'ExtraHosts')]:
            if host.get(field):
                service[setting] = copy.deepcopy(host[field])
        service['restart'] = (host.get('RestartPolicy') or {}).get('Name') or 'no'
        if service['restart'] == 'on-failure' and host['RestartPolicy'].get('MaximumRetryCount'):
            service['restart'] += ':' + str(host['RestartPolicy']['MaximumRetryCount'])
        log = host.get('LogConfig') or {}
        if log.get('Type'):
            service['logging'] = {'driver': log['Type'], 'options': log.get('Config') or {}}
        if config.get('StopSignal'):
            service['stop_signal'] = config['StopSignal']
        # Reuse actual Docker networks, avoiding a new network per release and
        # preserving access to Cassandra via the host's existing tunnel.
        service.pop('network_mode', None)
        service.pop('networks', None)
        model['networks'] = {}
        mode = host.get('NetworkMode') or 'bridge'
        if mode in ('bridge', 'host', 'none'):
            service['network_mode'] = mode
        else:
            names = list((original.get('NetworkSettings', {}).get('Networks') or {}))
            if mode not in names or not names:
                raise ComposeError('Existing production networks cannot be preserved')
            names = [mode] + [n for n in names if n != mode]
            service['networks'] = {}
            for index, network in enumerate(names):
                alias = 'retained' + str(index)
                model['networks'][alias] = {'external': True, 'name': network}
                endpoint = (configuration.get('NetworkingConfig', {}).get('EndpointsConfig') or {}).get(network, {})
                service['networks'][alias] = {'priority': len(names) - index,
                                             'aliases': endpoint.get('Aliases') or []}
        self.project = 'zapbot-deploy-' + transaction
        model['name'] = self.project
        self.plan = self.state_dir / ('compose-' + transaction + '.json')
        if self.plan.exists() or self.plan.is_symlink():
            raise ComposeError('Private Compose plan already exists; evidence retained')
        self.write(self.plan, json.dumps(model) + '\n')
        # Validate the frozen plan before stopping the healthy container.
        self.invoke([self.plan], ['config', '--quiet'], project=self.project)

    def create(self, engine, name):
        self.invoke([self.plan], ['create', '--no-build', '--pull', 'never', 'bot'], project=self.project)
        return engine.inspect(name)['Id']

    def start(self, engine, identifier, name):
        if engine.inspect(name)['Id'] != identifier:
            raise ComposeError('Compose start target identity changed')
        self.invoke([self.plan], ['start', 'bot'], project=self.project)
        if engine.inspect(name)['Id'] != identifier:
            raise ComposeError('Compose start changed the container identity')
