import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { ThemeProvider, useTheme } from '../context/ThemeContext';

const AdversarialConsumer: React.FC = () => {
  const { theme, toggleTheme, setTheme } = useTheme();
  return (
    <div>
      <span data-testid="theme-val">{theme}</span>
      <button data-testid="toggle-btn" onClick={toggleTheme}>Toggle</button>
      <button data-testid="set-dark-btn" onClick={() => setTheme('dark')}>Set Dark</button>
      <button data-testid="set-light-btn" onClick={() => setTheme('light')}>Set Light</button>
    </div>
  );
};

describe('Adversarial Stress Testing: ThemeContext & Persistence Engine', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.className = '';
    vi.restoreAllMocks();
  });

  it('Verification 1.1: ThemeContext defaults cleanly to "light" and document.documentElement has no "dark" class', () => {
    render(
      <ThemeProvider>
        <AdversarialConsumer />
      </ThemeProvider>
    );

    expect(screen.getByTestId('theme-val').textContent).toBe('light');
    expect(document.documentElement.classList.contains('dark')).toBe(false);
    expect(localStorage.getItem('hireflow_theme')).toBe('light');
  });

  it('Verification 1.2: Toggling to "dark" immediately adds "dark" class and updates localStorage["hireflow_theme"] to "dark"', () => {
    render(
      <ThemeProvider>
        <AdversarialConsumer />
      </ThemeProvider>
    );

    const toggleBtn = screen.getByTestId('toggle-btn');
    act(() => {
      fireEvent.click(toggleBtn);
    });

    expect(screen.getByTestId('theme-val').textContent).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    expect(localStorage.getItem('hireflow_theme')).toBe('dark');
  });

  it('Verification 1.3: Toggling back to "light" removes "dark" class and updates localStorage["hireflow_theme"] to "light"', () => {
    render(
      <ThemeProvider>
        <AdversarialConsumer />
      </ThemeProvider>
    );

    const toggleBtn = screen.getByTestId('toggle-btn');
    // First toggle to dark
    act(() => {
      fireEvent.click(toggleBtn);
    });
    expect(screen.getByTestId('theme-val').textContent).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);

    // Toggle back to light
    act(() => {
      fireEvent.click(toggleBtn);
    });
    expect(screen.getByTestId('theme-val').textContent).toBe('light');
    expect(document.documentElement.classList.contains('dark')).toBe(false);
    expect(localStorage.getItem('hireflow_theme')).toBe('light');
  });

  it('Verification 1.4: Adversarial invalid localStorage values ("neon", "null", "undefined", "{}", "DARK", empty string) gracefully fallback to "light"', () => {
    const invalidValues = ['neon', 'null', 'undefined', '{}', 'DARK', '', 'solarized', '123', 'false'];

    for (const invalidVal of invalidValues) {
      localStorage.clear();
      document.documentElement.className = '';
      localStorage.setItem('hireflow_theme', invalidVal);

      const { unmount } = render(
        <ThemeProvider>
          <AdversarialConsumer />
        </ThemeProvider>
      );

      // Must gracefully fallback to light without throwing or entering corrupted state
      expect(screen.getByTestId('theme-val').textContent).toBe('light');
      expect(document.documentElement.classList.contains('dark')).toBe(false);
      // Once rendered, it syncs the normalized 'light' value to storage
      expect(localStorage.getItem('hireflow_theme')).toBe('light');

      unmount();
    }
  });

  it('Verification 1.5: Thrown Storage Exception (e.g. SecurityError in iframe sandbox) falls back to "light" without crashing', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError');
    });

    render(
      <ThemeProvider>
        <AdversarialConsumer />
      </ThemeProvider>
    );

    expect(screen.getByTestId('theme-val').textContent).toBe('light');
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('Verification 1.6: Thrown Storage Exception on setItem (e.g. QuotaExceededError) allows toggling without crashing', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('QuotaExceededError', 'QuotaExceededError');
    });

    render(
      <ThemeProvider>
        <AdversarialConsumer />
      </ThemeProvider>
    );

    const toggleBtn = screen.getByTestId('toggle-btn');
    expect(() => {
      act(() => {
        fireEvent.click(toggleBtn);
      });
    }).not.toThrow();

    expect(screen.getByTestId('theme-val').textContent).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });

  it('Verification 1.7: Rapid consecutive toggles preserve DOM and state alignment', () => {
    render(
      <ThemeProvider>
        <AdversarialConsumer />
      </ThemeProvider>
    );

    const toggleBtn = screen.getByTestId('toggle-btn');

    for (let i = 0; i < 10; i++) {
      act(() => {
        fireEvent.click(toggleBtn);
      });
      const expectedTheme = (i % 2 === 0) ? 'dark' : 'light';
      expect(screen.getByTestId('theme-val').textContent).toBe(expectedTheme);
      expect(document.documentElement.classList.contains('dark')).toBe(expectedTheme === 'dark');
      expect(localStorage.getItem('hireflow_theme')).toBe(expectedTheme);
    }
  });
});
