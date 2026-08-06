export type AppEnvironment = 'DEV' | 'PROD';

const DEV_HOSTS = new Set([
  '127.0.0.1',
  'localhost',
  'dev.bili-comments.tyukki.com',
]);

export function resolveAppEnvironment(hostname = window.location.hostname): AppEnvironment {
  const configured = import.meta.env.VITE_APP_ENV;
  if (configured === 'DEV' || configured === 'PROD') return configured;
  return import.meta.env.DEV || DEV_HOSTS.has(hostname.toLowerCase()) ? 'DEV' : 'PROD';
}
