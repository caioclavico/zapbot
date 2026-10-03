'use strict';
// Cada módulo tem um escritor no processo. A tabela continua sendo a existente.
class State {
  constructor(domain) { this.domain = domain; this.queues = new Map(); }
  read(module) { return this.domain.load(module) || {}; }
  enqueue(module, action) {
    const previous = this.queues.get(module) || Promise.resolve();
    const next = previous.catch(() => {}).then(async () => {
      return action();
    });
    this.queues.set(module, next);
    next.finally(() => { if (this.queues.get(module) === next) this.queues.delete(module); }).catch(() => {});
    return next;
  }
  update(module, change) {
    return this.enqueue(module, async () => {
      const value = {...this.read(module)};
      const result = change(value);
      await this.domain.store(module, value);
      return result;
    });
  }
  reserve(module, key, value) {
    return this.enqueue(module, () => this.domain.reserve(module, key, value));
  }
}
module.exports = {State};
