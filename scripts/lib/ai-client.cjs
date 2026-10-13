'use strict';

const TRANSIENT = new Set([429, 500, 502, 503, 504]);
const positive = (value, fallback) => Number.isSafeInteger(value) && value > 0 ? value : fallback;
const configured = value => typeof value === 'string' && value.trim().length > 0;

function retryAfterMs(value, now = Date.now()) {
  if (!value) return 0;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000);
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - now) : 0;
}

function createClient(options = {}) {
  const {
    geminiApiKey, geminiModel, groqApiKey, groqModel = 'llama-3.1-8b-instant',
    systemPrompt, fetch: fetchFn = globalThis.fetch,
    logger = event => console.info(JSON.stringify(event)), now = Date.now,
    sleep = ms => new Promise(resolve => setTimeout(resolve, ms)),
  } = options;
  const requestTimeoutMs = positive(options.requestTimeoutMs, 8000);
  const totalTimeoutMs = positive(options.totalTimeoutMs, 30000);
  const retryBaseMs = positive(options.retryBaseMs, 1000);
  const groqEnabled = configured(groqApiKey);
  const emit = event => { try { logger({event: 'ai_request', ...event}); } catch {} };

  function request(provider, prompt) {
    if (provider === 'gemini') {
      return {
        url: `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(geminiModel)}:generateContent`,
        headers: {'Content-Type': 'application/json', 'x-goog-api-key': geminiApiKey},
        body: {systemInstruction: {parts: [{text: systemPrompt}]}, contents: [{role: 'user', parts: [{text: prompt}]}]},
      };
    }
    return {
      url: 'https://api.groq.com/openai/v1/chat/completions',
      headers: {'Content-Type': 'application/json', Authorization: `Bearer ${groqApiKey}`},
      body: {model: groqModel, messages: [{role: 'system', content: systemPrompt}, {role: 'user', content: prompt}]},
    };
  }

  async function attempt(provider, prompt, deadline, number) {
    const start = now();
    const timeout = Math.min(requestTimeoutMs, deadline - start);
    const model = provider === 'gemini' ? geminiModel : groqModel;
    if (timeout <= 0) return {error: 'deadline'};
    const controller = new AbortController();
    let timer;
    let timedOut = false;
    const expired = new Promise((_, reject) => {
      timer = setTimeout(() => {
        timedOut = true;
        controller.abort();
        reject(new Error('request_timeout'));
      }, timeout);
    });
    let result;
    try {
      const operation = (async () => {
        const {url, headers, body} = request(provider, prompt);
        const response = await fetchFn(url, {method: 'POST', headers, body: JSON.stringify(body), signal: controller.signal});
        if (!response.ok) {
          // Do not read or log provider error bodies: they can echo private input.
          return {error: 'http', status: response.status,
            retryAfter: retryAfterMs(response.headers?.get?.('retry-after'), now())};
        }
        const data = await response.json();
        const text = provider === 'gemini'
          ? data?.candidates?.[0]?.content?.parts?.filter(part => !part.thought && typeof part.text === 'string').map(part => part.text).join('')
          : data?.choices?.[0]?.message?.content;
        return typeof text === 'string' && text.trim() ? {text, status: response.status} : {error: 'empty_response', status: response.status};
      })();
      result = await Promise.race([operation, expired]);
    } catch (error) {
      result = {error: timedOut ? 'timeout' : error instanceof SyntaxError ? 'invalid_response' : 'network'};
    } finally {
      clearTimeout(timer);
    }
    emit({provider, model, attempt: number, latency_ms: Math.max(0, now() - start),
      outcome: result.text ? 'success' : 'error', ...(result.status ? {status: result.status} : {}),
      ...(result.error ? {error: result.error} : {})});
    return result;
  }

  async function generate(prompt) {
    const start = now();
    const deadline = start + totalTimeoutMs;
    // Reserve one bounded request for fallback; all waits count toward the total.
    const primaryDeadline = groqEnabled ? Math.max(start, deadline - Math.min(requestTimeoutMs, totalTimeoutMs / 2)) : deadline;
    if (configured(geminiApiKey)) {
      for (let number = 1; number <= 3 && now() < primaryDeadline; number++) {
        const result = await attempt('gemini', prompt, primaryDeadline, number);
        if (result.text) {
          emit({provider: 'gemini', model: geminiModel, outcome: 'complete', latency_ms: now() - start});
          return result.text;
        }
        const transient = TRANSIENT.has(result.status) || ['network', 'timeout'].includes(result.error);
        if (!transient || number === 3) break;
        const delay = Math.max(retryBaseMs * 2 ** (number - 1), result.retryAfter || 0);
        if (now() + delay >= primaryDeadline) break; // Never retry before Retry-After to fit a deadline.
        emit({provider: 'gemini', model: geminiModel, outcome: 'retry', retry_in_ms: delay});
        await sleep(delay);
      }
    }
    if (groqEnabled && now() < deadline) {
      emit({provider: 'groq', model: groqModel, outcome: 'fallback'});
      const result = await attempt('groq', prompt, deadline, 1);
      if (result.text) {
        emit({provider: 'groq', model: groqModel, outcome: 'complete', latency_ms: now() - start});
        return result.text;
      }
    }
    emit({outcome: 'unavailable', latency_ms: Math.max(0, now() - start)});
    return null;
  }
  return {generate};
}

module.exports = {createClient, retryAfterMs};
