/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_APP_ENV?: 'DEV' | 'PROD';
  readonly VITE_APP_VERSION?: string;
  readonly VITE_ENABLE_MOCKS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
