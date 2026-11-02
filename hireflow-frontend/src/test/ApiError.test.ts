import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { api } from '../services/api';

describe('API Service - Error Normalization & Actuator Health', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('normalizes TypeError: Failed to fetch into diagnostic actionable error message', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(api.getHealth()).rejects.toThrow(
      'HireFlow backend is unreachable. Please verify the service is running on port 8080.'
    );
  });

  it('normalizes NetworkError into diagnostic actionable error message', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('NetworkError when attempting to fetch resource.'));

    await expect(api.getHealth()).rejects.toThrow(
      'HireFlow backend is unreachable. Please verify the service is running on port 8080.'
    );
  });

  it('returns status UP from getSystemHealth when actuator responds with UP', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ status: 'UP' }),
    } as unknown as Response);

    const result = await api.getSystemHealth();
    expect(result).toEqual({ status: 'UP' });
    expect(globalThis.fetch).toHaveBeenCalledWith('/actuator/health');
  });

  it('returns status DOWN from getSystemHealth when actuator response is not ok', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      json: async () => ({ status: 'DOWN' }),
    } as unknown as Response);

    const result = await api.getSystemHealth();
    expect(result).toEqual({ status: 'DOWN' });
  });

  it('returns status DOWN from getSystemHealth when actuator fetch fails completely', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    const result = await api.getSystemHealth();
    expect(result).toEqual({ status: 'DOWN' });
  });
});
