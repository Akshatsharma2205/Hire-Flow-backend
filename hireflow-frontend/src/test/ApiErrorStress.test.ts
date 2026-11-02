import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { api, setOnUnauthorizedCallback } from '../services/api';

describe('Adversarial Stress Harness: Error Normalization & Actuator Health', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  // ==========================================
  // TASK 1: Error Normalization Stress Testing
  // ==========================================
  describe('Task 1: Error Normalization Stress Testing', () => {
    it('catches TypeError: Failed to fetch and transforms it into diagnostic message with port 8080', async () => {
      globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

      await expect(api.getCandidates()).rejects.toThrow(
        'HireFlow backend is unreachable. Please verify the service is running on port 8080.'
      );
      await expect(api.login({ email: 'test@example.com', password: 'secret' })).rejects.toThrow(
        'HireFlow backend is unreachable. Please verify the service is running on port 8080.'
      );
    });

    it('catches browser network error variants (Firefox, WebKit, React Native) and mentions port 8080', async () => {
      // Firefox
      globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('NetworkError when attempting to fetch resource.'));
      await expect(api.getJobs()).rejects.toThrow(
        'HireFlow backend is unreachable. Please verify the service is running on port 8080.'
      );

      // React Native / fetch polyfill
      globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Network request failed'));
      await expect(api.getApplications()).rejects.toThrow(
        'HireFlow backend is unreachable. Please verify the service is running on port 8080.'
      );

      // Case-insensitive check
      globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('TypeError: failed to fetch'));
      await expect(api.getRules()).rejects.toThrow(
        'HireFlow backend is unreachable. Please verify the service is running on port 8080.'
      );
    });

    it('preserves AbortError and TimeoutError without corrupting message or converting to port 8080 error', async () => {
      // AbortError DOMException
      const abortError = new DOMException('The user aborted a request.', 'AbortError');
      globalThis.fetch = vi.fn().mockRejectedValue(abortError);

      await expect(api.getJobs()).rejects.toThrow('The user aborted a request.');
      await expect(api.getJobs()).rejects.toHaveProperty('name', 'AbortError');

      // TimeoutError DOMException
      const timeoutError = new DOMException('The operation timed out.', 'TimeoutError');
      globalThis.fetch = vi.fn().mockRejectedValue(timeoutError);

      await expect(api.getJobs()).rejects.toThrow('The operation timed out.');
      await expect(api.getJobs()).rejects.toHaveProperty('name', 'TimeoutError');

      // Generic client-side TypeError that is NOT a network failure (e.g. programming error)
      const otherTypeError = new TypeError('Cannot read properties of undefined (reading foo)');
      globalThis.fetch = vi.fn().mockRejectedValue(otherTypeError);

      await expect(api.getJobs()).rejects.toThrow('Cannot read properties of undefined (reading foo)');
    });

    it('handles HTTP 400 Bad Request with json message or error', async () => {
      // JSON with message field
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: async () => ({ message: 'Email address is invalid' }),
      } as unknown as Response);

      await expect(api.createJob({ title: 'Engineer', description: 'Desc' })).rejects.toThrow(
        'Email address is invalid'
      );

      // JSON with error field
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: async () => ({ error: 'Payload validation failure' }),
      } as unknown as Response);

      await expect(api.createJob({ title: 'Engineer', description: 'Desc' })).rejects.toThrow(
        'Payload validation failure'
      );
    });

    it('handles HTTP 401 Unauthorized, triggers callback, and parses json error or message', async () => {
      const unauthorizedCallback = vi.fn();
      setOnUnauthorizedCallback(unauthorizedCallback);

      // JSON with message field
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        statusText: 'Unauthorized',
        json: async () => ({ message: 'JWT token has expired' }),
      } as unknown as Response);

      await expect(api.getReviewCases()).rejects.toThrow('JWT token has expired');
      expect(unauthorizedCallback).toHaveBeenCalledTimes(1);

      // JSON with error field
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        statusText: 'Unauthorized',
        json: async () => ({ error: 'Bad credentials' }),
      } as unknown as Response);

      await expect(api.getReviewCases()).rejects.toThrow('Bad credentials');
      expect(unauthorizedCallback).toHaveBeenCalledTimes(2);

      // Non-JSON 401 body (e.g., Spring Security default empty or text body)
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        statusText: 'Unauthorized',
        json: async () => {
          throw new Error('Unexpected token < in JSON at position 0');
        },
      } as unknown as Response);

      await expect(api.getReviewCases()).rejects.toThrow('Unauthorized');
      expect(unauthorizedCallback).toHaveBeenCalledTimes(3);
    });

    it('handles HTTP 404 Not Found with json message, error, or fallback statusText', async () => {
      // JSON with message
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        json: async () => ({ message: 'Job not found with id abc-123' }),
      } as unknown as Response);

      await expect(api.getJob('abc-123')).rejects.toThrow('Job not found with id abc-123');

      // Non-JSON 404 (e.g. gateway 404 HTML)
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        json: async () => {
          throw new SyntaxError('Unexpected token');
        },
      } as unknown as Response);

      await expect(api.getJob('missing')).rejects.toThrow('HTTP 404: Not Found');
    });

    it('handles HTTP 500 Internal Server Error and HTTP 502/503 upstream gateway errors', async () => {
      // 500 with json error
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: async () => ({ error: 'Database connection pool exhausted' }),
      } as unknown as Response);

      await expect(api.getMailConnections()).rejects.toThrow('Database connection pool exhausted');

      // 500 with non-JSON body (e.g. raw stacktrace or HTML)
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: async () => {
          throw new SyntaxError('Invalid JSON');
        },
      } as unknown as Response);

      await expect(api.getMailConnections()).rejects.toThrow('HTTP 500: Internal Server Error');

      // 502 Bad Gateway
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        statusText: 'Bad Gateway',
        json: async () => null,
      } as unknown as Response);

      await expect(api.getMailConnections()).rejects.toThrow('HTTP 502: Bad Gateway');
    });

    it('handles HTTP 204 No Content gracefully without attempting json parse', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 204,
        statusText: 'No Content',
        headers: new Headers(),
      } as unknown as Response);

      const res = await api.deleteRule('rule-1');
      expect(res).toEqual({});
    });
  });

  // ==========================================
  // TASK 2: Actuator Health Probe Stress Testing
  // ==========================================
  describe('Task 2: Actuator Health Probe Stress Testing', () => {
    it('returns { status: "UP" } when /actuator/health responds with HTTP 200 and {"status":"UP"}', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ status: 'UP', components: { db: { status: 'UP' } } }),
      } as unknown as Response);

      const res = await api.getSystemHealth();
      expect(res).toEqual({ status: 'UP' });
      expect(globalThis.fetch).toHaveBeenCalledWith('/actuator/health');
    });

    it('returns { status: "DOWN" } without throwing when /actuator/health responds with HTTP 503', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
        statusText: 'Service Unavailable',
        json: async () => ({ status: 'DOWN', components: { db: { status: 'DOWN' } } }),
      } as unknown as Response);

      const res = await api.getSystemHealth();
      expect(res).toEqual({ status: 'DOWN' });
    });

    it('returns { status: "DOWN" } without throwing when /actuator/health fails with network refusal', async () => {
      // TypeError: Failed to fetch (connection refused / server down)
      globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

      const res1 = await api.getSystemHealth();
      expect(res1).toEqual({ status: 'DOWN' });

      // Socket error or connection aborted
      globalThis.fetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED 127.0.0.1:8080'));

      const res2 = await api.getSystemHealth();
      expect(res2).toEqual({ status: 'DOWN' });
    });

    it('returns { status: "DOWN" } when actuator returns 200 with {"status":"DOWN"}', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ status: 'DOWN' }),
      } as unknown as Response);

      const res = await api.getSystemHealth();
      expect(res).toEqual({ status: 'DOWN' });
    });

    it('returns { status: "UNKNOWN" } when actuator returns 200 with an unmapped status', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ status: 'OUT_OF_SERVICE' }),
      } as unknown as Response);

      const res = await api.getSystemHealth();
      expect(res).toEqual({ status: 'UNKNOWN' });
    });

    it('returns { status: "UNKNOWN" } when actuator returns 200 with invalid json', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => {
          throw new SyntaxError('Unexpected token');
        },
      } as unknown as Response);

      const res = await api.getSystemHealth();
      expect(res).toEqual({ status: 'UNKNOWN' });
    });

    it('never throws even if an extreme runtime exception occurs inside health probe', async () => {
      globalThis.fetch = vi.fn().mockImplementation(() => {
        throw new Error('Fatal internal exception');
      });

      let res;
      expect(async () => {
        res = await api.getSystemHealth();
      }).not.toThrow();

      res = await api.getSystemHealth();
      expect(res).toEqual({ status: 'DOWN' });
    });
  });
});
