import React, { useEffect } from 'react';
import { X } from 'lucide-react';

interface DrawerProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  subtitle?: string;
  children: React.ReactNode;
  width?: 'md' | 'lg' | 'xl' | '2xl' | '3xl';
}

export const Drawer: React.FC<DrawerProps> = ({
  isOpen,
  onClose,
  title,
  subtitle,
  children,
  width = 'xl',
}) => {
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isOpen) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const widthClasses = {
    md: 'max-w-md',
    lg: 'max-w-lg',
    xl: 'max-w-xl',
    '2xl': 'max-w-2xl',
    '3xl': 'max-w-3xl',
  };

  return (
    <div className="fixed inset-0 z-50 overflow-hidden">
      {/* Backdrop */}
      <div
        className="fixed inset-0 animate-in bg-slate-950/45 backdrop-blur-[3px] transition-opacity duration-150 fade-in dark:bg-slate-950/75"
        onClick={onClose}
        aria-hidden="true"
      />

      <div className="fixed inset-y-0 right-0 flex max-w-full pl-6 sm:pl-10">
        <div
          role="dialog"
          aria-modal="true"
          className={`flex w-screen ${widthClasses[width]} animate-in flex-col border-l border-slate-200 bg-white text-slate-900 shadow-2xl duration-200 ease-out slide-in-from-right dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100`}
        >
          {/* Header */}
          <div className="flex items-start justify-between border-b border-slate-200 bg-white/95 px-6 py-5 backdrop-blur dark:border-slate-800 dark:bg-slate-900/95">
            <div>
              <h2 className="text-[17px] font-semibold tracking-[-0.02em] text-slate-950 dark:text-slate-50">{title}</h2>
              {subtitle && <p className="mt-1 text-[12px] leading-5 text-slate-500 dark:text-slate-400">{subtitle}</p>}
            </div>
            <button
              onClick={onClose}
              className="-mr-1 rounded-lg p-1.5 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800 dark:hover:text-white"
              aria-label="Close drawer"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          {/* Body */}
          <div className="flex-1 space-y-6 overflow-y-auto px-6 py-6">{children}</div>
        </div>
      </div>
    </div>
  );
};
