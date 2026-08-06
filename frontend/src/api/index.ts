import type { ApiService } from './service';
import { httpApi } from './http';
import { mockApi } from './mock';

export const mocksEnabled = import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCKS === 'true';

export const api: ApiService = mocksEnabled ? mockApi : httpApi;

export { ApiError } from './service';
export type * from './types';
