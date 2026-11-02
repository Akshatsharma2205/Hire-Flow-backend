import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { Drawer } from '../components/common/Drawer';
import { ToastProvider, useToast } from '../components/common/Toast';

describe('Common UI Primitives', () => {
  describe('Button', () => {
    it('renders primary variant with teal brand classes', () => {
      render(<Button variant="primary">Submit Application</Button>);
      const btn = screen.getByRole('button', { name: /Submit Application/i });
      expect(btn).toBeInTheDocument();
      expect(btn.className).toContain('bg-teal-700');
      expect(btn.className).toContain('dark:bg-teal-500');
    });

    it('renders secondary variant with dual-theme classes', () => {
      render(<Button variant="secondary">Cancel</Button>);
      const btn = screen.getByRole('button', { name: /Cancel/i });
      expect(btn.className).toContain('bg-white');
      expect(btn.className).toContain('dark:bg-slate-800');
      expect(btn.className).toContain('border-slate-200');
    });

    it('shows loading spinner and disables button when isLoading is true', () => {
      render(<Button isLoading>Saving</Button>);
      const btn = screen.getByRole('button');
      expect(btn).toBeDisabled();
      expect(btn.querySelector('.animate-spin')).toBeInTheDocument();
    });

    it('handles disabled state properly', () => {
      render(<Button disabled>Disabled Action</Button>);
      const btn = screen.getByRole('button', { name: /Disabled Action/i });
      expect(btn).toBeDisabled();
      expect(btn.className).toContain('disabled:opacity-50');
    });

    it('renders sizes sm, md, and lg', () => {
      const { rerender } = render(<Button size="sm">Small</Button>);
      expect(screen.getByRole('button').className).toContain('text-xs');

      rerender(<Button size="md">Medium</Button>);
      expect(screen.getByRole('button').className).toContain('text-sm');

      rerender(<Button size="lg">Large</Button>);
      expect(screen.getByRole('button').className).toContain('text-base');
    });
  });

  describe('Badge', () => {
    it('renders pastel light background and dark variant', () => {
      render(<Badge variant="emerald">Active</Badge>);
      const badge = screen.getByText('Active').closest('span');
      expect(badge?.className).toContain('bg-emerald-50');
      expect(badge?.className).toContain('text-emerald-700');
      expect(badge?.className).toContain('dark:bg-emerald-950/60');
    });

    it('renders teal badge variant', () => {
      render(<Badge variant="teal">Hired</Badge>);
      const badge = screen.getByText('Hired').closest('span');
      expect(badge?.className).toContain('bg-teal-50');
      expect(badge?.className).toContain('text-teal-700');
      expect(badge?.className).toContain('dark:bg-teal-950/60');
    });

    it('renders with dot indicator when dot prop is passed', () => {
      render(
        <Badge variant="amber" dot>
          Pending Review
        </Badge>
      );
      const dot = document.querySelector('.bg-amber-500');
      expect(dot).toBeInTheDocument();
    });
  });

  describe('Modal', () => {
    it('renders modal content when isOpen is true', () => {
      const onClose = vi.fn();
      render(
        <Modal
          isOpen={true}
          onClose={onClose}
          title="Upload Resume"
          subtitle="Support PDF and DOCX"
          footer={<button>Confirm</button>}
        >
          <p>Drop file here</p>
        </Modal>
      );

      expect(screen.getByRole('dialog')).toBeInTheDocument();
      expect(screen.getByText('Upload Resume')).toBeInTheDocument();
      expect(screen.getByText('Support PDF and DOCX')).toBeInTheDocument();
      expect(screen.getByText('Drop file here')).toBeInTheDocument();
      expect(screen.getByText('Confirm')).toBeInTheDocument();
    });

    it('does not render when isOpen is false', () => {
      render(
        <Modal isOpen={false} onClose={() => {}} title="Hidden Modal">
          <p>Hidden</p>
        </Modal>
      );
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('calls onClose when close button is clicked', () => {
      const onClose = vi.fn();
      render(
        <Modal isOpen={true} onClose={onClose} title="Test Modal">
          <p>Content</p>
        </Modal>
      );

      const closeBtn = screen.getByLabelText('Close modal');
      fireEvent.click(closeBtn);
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('calls onClose on Escape key press', () => {
      const onClose = vi.fn();
      render(
        <Modal isOpen={true} onClose={onClose} title="Escape Modal">
          <p>Content</p>
        </Modal>
      );

      fireEvent.keyDown(window, { key: 'Escape' });
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  describe('Drawer', () => {
    it('renders drawer when isOpen is true', () => {
      const onClose = vi.fn();
      render(
        <Drawer
          isOpen={true}
          onClose={onClose}
          title="Candidate Profile"
          subtitle="Senior Engineer"
        >
          <p>Skills: React, TypeScript</p>
        </Drawer>
      );

      expect(screen.getByRole('dialog')).toBeInTheDocument();
      expect(screen.getByText('Candidate Profile')).toBeInTheDocument();
      expect(screen.getByText('Senior Engineer')).toBeInTheDocument();
      expect(screen.getByText('Skills: React, TypeScript')).toBeInTheDocument();
    });

    it('calls onClose when close button is clicked', () => {
      const onClose = vi.fn();
      render(
        <Drawer isOpen={true} onClose={onClose} title="Drawer Test">
          <p>Content</p>
        </Drawer>
      );

      const closeBtn = screen.getByLabelText('Close drawer');
      fireEvent.click(closeBtn);
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('calls onClose on Escape key', () => {
      const onClose = vi.fn();
      render(
        <Drawer isOpen={true} onClose={onClose} title="Drawer Escape">
          <p>Content</p>
        </Drawer>
      );

      fireEvent.keyDown(window, { key: 'Escape' });
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  describe('Toast', () => {
    const ToastTester: React.FC = () => {
      const { toast } = useToast();
      return (
        <div>
          <button onClick={() => toast('Operation succeeded', 'success')}>
            Show Success
          </button>
          <button onClick={() => toast('Network error occurred', 'error')}>
            Show Error
          </button>
        </div>
      );
    };

    it('renders toast notification and allows manual dismissal', () => {
      render(
        <ToastProvider>
          <ToastTester />
        </ToastProvider>
      );

      fireEvent.click(screen.getByText('Show Success'));
      expect(screen.getByText('Operation succeeded')).toBeInTheDocument();

      const dismissBtn = screen.getByLabelText('Dismiss toast');
      fireEvent.click(dismissBtn);
      expect(screen.queryByText('Operation succeeded')).not.toBeInTheDocument();
    });

    it('renders error toast notification', () => {
      render(
        <ToastProvider>
          <ToastTester />
        </ToastProvider>
      );

      fireEvent.click(screen.getByText('Show Error'));
      expect(screen.getByText('Network error occurred')).toBeInTheDocument();
    });
  });
});
