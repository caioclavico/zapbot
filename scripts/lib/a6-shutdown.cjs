'use strict';

/** Coordinated Node shutdown. Drain failure is fail-closed: resources stay up. */
function createShutdown({
  drain,
  beforeDrain = () => {},
  stopAdmissionSources = async () => {},
  closeServer = async () => {},
  closeRecovery = async () => {},
  stopResources = async () => {},
  stopPokemon = async () => {},
  updateHealth = async () => {},
  exit = (code) => process.exit(code),
  log = () => {},
  deadlineMs = 45000,
  schedule = setTimeout,
  cancel = clearTimeout
}) {
  let state = 'open';
  let current = null;
  let deadline = null;

  async function run() {
    let drainedThisRun = false;
    let sourceStopFailed = false;
    state = 'draining';
    try {
      // Calling drain closes the durable gate synchronously before returning its Promise.
      const pendingDrain = drain();
      try { await stopAdmissionSources(); }
      catch (error) {
        sourceStopFailed = true;
        log('shutdown_source_stop_failed', { code: error?.code || 'source_stop_error' });
      }
      const result = await pendingDrain;
      if (!result || result.quiescent !== true) {
        state = 'blocked';
        log('shutdown_drain_incomplete', result || {});
        return { stopped: false, reason: 'drain_incomplete', result };
      }
      if (result.durable === false) {
        state = 'blocked';
        log('shutdown_drain_not_durable', { activeOperations: result.activeOperations || 0 });
        return { stopped: false, reason: 'drain_not_durable', result };
      }
      drainedThisRun = true;

      state = 'closing';
      const failures = [];
      if (sourceStopFailed) {
        try { await stopAdmissionSources(); }
        catch (error) { failures.push({ phase: 'stop_admission_sources', code: error?.code || 'source_stop_error' }); }
      }
      for (const [name, step] of [
        ['stop_resources', stopResources],
        ['update_health', updateHealth],
        ['stop_pokemon', stopPokemon],
        ['close_health_server', closeServer],
        ['close_whatsapp_recovery', closeRecovery]
      ]) {
        try { await step(); }
        catch (error) { failures.push({ phase: name, code: error?.code || 'cleanup_error' }); }
      }
      if (failures.length) {
        state = 'failed';
        log('shutdown_cleanup_incomplete', { failures });
        exit(1);
        return { stopped: false, reason: 'cleanup_incomplete', failures };
      }
      state = 'closed';
      if (deadline !== null) cancel(deadline);
      deadline = null;
      exit(0);
      return { stopped: true };
    } catch (error) {
      state = 'failed';
      log('shutdown_failed', { phase: state, code: error?.code || 'shutdown_error' });
      // If drain succeeded, an orderly resource close failed; nonzero exit is honest.
      // If drain itself threw, leave all resources alive and admission fail-closed.
      if (drainedThisRun) exit(1);
      return { stopped: false, reason: 'shutdown_failed', code: error?.code || 'shutdown_error' };
    }
  }

  return {
    request(signal = 'SIGTERM') {
      if (state === 'closed') return Promise.resolve({ stopped: true });
      if (current) {
        log('shutdown_signal_coalesced', { signal, state });
        return current;
      }
      if (deadline === null) {
        deadline = schedule(() => {
          if (state !== 'closed') {
            state = 'timed_out';
            log('shutdown_deadline_exceeded', { signal });
            // Do not kill a possibly committed transaction here. The supervisor's
            // stop timeout remains the final bound; the service stays fail-closed.
          }
        }, deadlineMs);
        deadline?.unref?.();
      }
      try { beforeDrain(signal); }
      catch (error) {
        state = 'failed';
        log('shutdown_admission_close_failed', { code: error?.code || 'admission_close_error' });
        return Promise.resolve({ stopped: false, reason: 'admission_close_failed' });
      }
      current = run().finally(() => { current = null; });
      return current;
    },
    status() { return state; }
  };
}

module.exports = { createShutdown };
