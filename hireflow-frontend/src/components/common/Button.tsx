import React, { ButtonHTMLAttributes } from 'react';
import { Loader2 } from 'lucide-react';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  isLoading?: boolean;
  icon?: React.ReactNode;
}

export const Button: React.FC<ButtonProps> = ({
  children,
  variant = 'primary',
  size = 'md',
  isLoading = false,
  icon,
  className = '',
  disabled,
  ...props
}) => {
  const baseStyles =
    'inline-flex items-center justify-center font-medium transition-all duration-150 focus:outline-none focus:ring-2 focus:ring-teal-500/50 focus:ring-offset-2 focus:ring-offset-white dark:focus:ring-offset-slate-900 disabled:opacity-50 disabled:cursor-not-allowed select-none rounded-lg cursor-pointer';

  const sizeStyles = {
    sm: 'text-xs px-2.5 py-1.5 gap-1.5',
    md: 'text-sm px-3.5 py-2 gap-2',
    lg: 'text-base px-5 py-2.5 gap-2.5 rounded-xl',
  };

  const variantStyles = {
    primary:
      'bg-teal-700 hover:bg-teal-800 active:bg-teal-900 text-white shadow-sm shadow-teal-950/20 dark:bg-teal-500 dark:hover:bg-teal-400 dark:active:bg-teal-600 dark:text-slate-950',
    secondary:
      'bg-white hover:bg-slate-50 active:bg-slate-100 text-slate-700 border border-slate-200 shadow-sm dark:bg-slate-800 dark:hover:bg-slate-700 dark:active:bg-slate-700 dark:text-slate-100 dark:border-slate-700',
    outline:
      'border border-slate-300 hover:border-slate-400 text-slate-700 hover:text-slate-900 bg-transparent hover:bg-slate-50 dark:border-slate-700 dark:hover:border-slate-600 dark:text-slate-300 dark:hover:text-white dark:hover:bg-slate-800/60',
    ghost:
      'text-slate-600 hover:text-slate-900 hover:bg-slate-100 active:bg-slate-200 dark:text-slate-400 dark:hover:text-slate-100 dark:hover:bg-slate-800/60 dark:active:bg-slate-800',
    danger:
      'bg-rose-600 hover:bg-rose-500 active:bg-rose-700 text-white shadow-sm shadow-rose-950/20 dark:bg-rose-600 dark:hover:bg-rose-500',
  };

  return (
    <button
      disabled={disabled || isLoading}
      className={`${baseStyles} ${sizeStyles[size]} ${variantStyles[variant]} ${className}`}
      {...props}
    >
      {isLoading ? (
        <Loader2 className="w-4 h-4 animate-spin text-current" />
      ) : (
        icon && <span className="inline-flex shrink-0">{icon}</span>
      )}
      <span>{children}</span>
    </button>
  );
};
