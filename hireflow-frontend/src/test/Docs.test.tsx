import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import React from 'react';
import { DocsPage } from '../pages/DocsPage';
import { ToastProvider } from '../components/common/Toast';

describe('Documentation Viewer Page', () => {
  it('renders technical documentation catalog categories and articles', () => {
    render(
      <ToastProvider>
        <DocsPage />
      </ToastProvider>
    );

    const archElements = screen.getAllByText(/Architecture & Blueprint/i);
    expect(archElements.length).toBeGreaterThanOrEqual(1);

    expect(screen.getByText(/API Reference & Integrations/i)).toBeInTheDocument();
    expect(screen.getByText(/Database & Data Models/i)).toBeInTheDocument();
  });

  it('filters documents when searching keywords', () => {
    render(
      <ToastProvider>
        <DocsPage />
      </ToastProvider>
    );

    const searchInput = screen.getByPlaceholderText(/Search documentation.../i);
    fireEvent.change(searchInput, { target: { value: 'Flyway' } });

    expect(screen.getByText(/Database Schema & Flyway/i)).toBeInTheDocument();
  });
});
