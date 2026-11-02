import React, { createContext, useContext, useState, ReactNode } from 'react';
import { CheckCircle2, AlertCircle, Info, X } from 'lucide-react';

export type ToastType = 'success' | 'error' | 'info';

interface ToastItem {
  id: string;
  message: string;
  type: ToastType;
}

interface ToastContextType {
  toast: (message: string, type?: ToastType) => void;
}

const ToastContext = createContext<ToastContextType | undefined>(undefined);

export const ToastProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const toast = (message: string, type: ToastType = 'info') => {
    const id = Math.random().toString(36).substring(2, 9);
    setToasts((prev) => [...prev, { id, message, type }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 4000);
  };

  const removeToast = (id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  };

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div className="pointer-events-none fixed bottom-5 right-5 z-50 flex max-w-sm flex-col gap-2">
        {toasts.map((t) => (
          <div
            key={t.id}
            role="alert"
            className={`pointer-events-auto flex animate-in items-start gap-3 rounded-xl border bg-white/95 p-3.5 text-sm text-slate-900 shadow-lg backdrop-blur transition-all slide-in-from-bottom-2 dark:bg-slate-900/95 dark:text-slate-100 ${
              t.type === 'success'
                ? 'border-emerald-500/40 shadow-emerald-950/10 dark:border-emerald-500/40 dark:shadow-emerald-950/20'
                : t.type === 'error'
                ? 'border-rose-500/40 shadow-rose-950/10 dark:border-rose-500/40 dark:shadow-rose-950/20'
                : 'border-slate-200 shadow-slate-950/10 dark:border-slate-700 dark:shadow-slate-950/50'
            }`}
          >
            {t.type === 'success' && <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600 dark:text-emerald-400" />}
            {t.type === 'error' && <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-rose-600 dark:text-rose-400" />}
            {t.type === 'info' && <Info className="mt-0.5 h-4 w-4 shrink-0 text-slate-500 dark:text-slate-400" />}
            <span className="flex-1 text-[12px] leading-5">{t.message}</span>
            <button
              onClick={() => removeToast(t.id)}
              className="rounded p-0.5 text-slate-400 transition-colors hover:text-slate-700 dark:text-slate-500 dark:hover:text-slate-300"
              aria-label="Dismiss toast"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
};

export const useToast = (): ToastContextType => {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within ToastProvider');
  }
  return context;
};
