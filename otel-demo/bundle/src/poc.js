// Injected by the proxy into every HTML page. Settings come from the script tag's data-* attributes
// and all traffic stays on the page's own origin under /__poc/.
import { openobserveRum } from '@openobserve/browser-rum';
import { openobserveLogs } from '@openobserve/browser-logs';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
import { UserInteractionInstrumentation } from '@opentelemetry/instrumentation-user-interaction';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { XMLHttpRequestInstrumentation } from '@opentelemetry/instrumentation-xml-http-request';
import { resourceFromAttributes } from '@opentelemetry/resources';

const cfg = document.currentScript.dataset;
const base = location.origin + '/__poc';

const startRum = (token) => {
  const common = {
    clientToken: token,
    site: location.host + '/__poc/o2',
    insecureHTTP: location.protocol === 'http:',
    organizationIdentifier: cfg.org,
    service: cfg.app,
    env: cfg.env,
    version: cfg.version,
  };
  openobserveRum.init({
    ...common,
    applicationId: cfg.app,
    sessionSampleRate: 100,
    sessionReplaySampleRate: 100,
    defaultPrivacyLevel: cfg.privacy,
    trackUserInteractions: true,
    trackResources: true,
    trackLongTasks: true,
  });
  openobserveRum.startSessionReplayRecording();
  openobserveLogs.init({ ...common, forwardErrorsToLogs: true, sessionSampleRate: 100 });
};

fetch(base + '/o2/token')
  .then((r) => r.json())
  .then((j) => startRum(j.data.rum_token))
  .catch((e) => console.warn('usage tracking: replay is off, could not get the RUM token', e));

const batch = new BatchSpanProcessor(new OTLPTraceExporter({ url: base + '/otlp/v1/traces' }));

const pageContext = {
  onStart(span) {
    span.setAttribute('page.path', location.pathname);
    const session = openobserveRum.getInternalContext()?.session_id;
    if (session) span.setAttribute('rum.session_id', session);
  },
  // Send interactions right away: a click that navigates would otherwise leave them for the
  // page-hide flush, which loses to the replay upload for the browser's keepalive budget.
  onEnd(span) {
    if (span.attributes.event_type) batch.forceFlush();
  },
  forceFlush: () => Promise.resolve(),
  shutdown: () => Promise.resolve(),
};

const provider = new WebTracerProvider({
  resource: resourceFromAttributes({
    'service.name': cfg.app,
    'service.version': cfg.version,
    'deployment.environment.name': cfg.env,
    'app.host': location.host,
  }),
  spanProcessors: [batch, pageContext],
});
provider.register();

// A human name for the control, never what the user typed.
const label = (el) => {
  const text =
    el.getAttribute('aria-label') ||
    el.labels?.[0]?.innerText ||
    (el.tagName === 'FORM' ? el.getAttribute('name') || el.getAttribute('action') : '') ||
    (['SELECT', 'FORM', 'TEXTAREA'].includes(el.tagName) ? '' : el.innerText) ||
    (['submit', 'button', 'reset'].includes(el.type) ? el.value : '') ||
    el.title ||
    el.getAttribute('name') ||
    '';
  return text.trim().slice(0, 100);
};

const ownTraffic = [/\/__poc\//];
const interactions = ['click', 'submit', 'change'];
registerInstrumentations({
  instrumentations: [
    new DocumentLoadInstrumentation(),
    new UserInteractionInstrumentation({
      eventNames: interactions,
      // The instrumentation opens a nested span per listener; keep only the outermost one.
      shouldPreventSpanCreation: (_event, el, span) => {
        if (span.parentSpanContext) return true;
        span.setAttribute('target.id', el.id || '');
        span.setAttribute('target.name', el.getAttribute('name') || '');
        span.setAttribute('target.text', label(el));
      },
    }),
    new FetchInstrumentation({ ignoreUrls: ownTraffic }),
    new XMLHttpRequestInstrumentation({ ignoreUrls: ownTraffic }),
  ],
});

// Spans only come from listeners added through addEventListener, which legacy pages using onclick
// attributes never do. This patched no-op listener guarantees one span per interaction.
for (const type of interactions) document.addEventListener(type, () => {}, true);
