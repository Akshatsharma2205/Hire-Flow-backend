import React from 'react';

type MarkSize = 'sm' | 'md' | 'lg';

interface BrandMarkProps {
  size?: MarkSize;
  className?: string;
}

const markSizes: Record<MarkSize, string> = {
  sm: 'h-8 w-8 rounded-[9px]',
  md: 'h-10 w-10 rounded-[11px]',
  lg: 'h-12 w-12 rounded-[13px]',
};

/**
 * HireFlow's product mark: three intake lines narrowing into a reviewed flow.
 * Deliberately geometric rather than illustrative, so it reads as a real
 * product mark at 16px and at 48px.
 */
export const BrandMark: React.FC<BrandMarkProps> = ({ size = 'md', className = '' }) => (
  <span
    className={`inline-flex shrink-0 items-center justify-center bg-teal-700 text-paper-50 shadow-sm ring-1 ring-inset ring-white/10 ${markSizes[size]} ${className}`}
    aria-hidden="true"
  >
    <svg viewBox="0 0 24 24" fill="none" className="h-[58%] w-[58%]">
      <path
        d="M4.5 6.75h15M4.5 12h9.75M4.5 17.25h5.25"
        stroke="currentColor"
        strokeWidth="1.9"
        strokeLinecap="round"
      />
      <circle cx="17.25" cy="12" r="1.35" fill="currentColor" />
      <circle cx="13.5" cy="17.25" r="1.35" fill="currentColor" />
    </svg>
  </span>
);

interface WordmarkProps {
  markSize?: MarkSize;
  subtitle?: string;
  className?: string;
  markClassName?: string;
}

export const Wordmark: React.FC<WordmarkProps> = ({
  markSize = 'md',
  subtitle,
  className = '',
  markClassName = '',
}) => (
  <span className={`inline-flex items-center gap-3 ${className}`}>
    <BrandMark size={markSize} className={markClassName} />
    <span className="flex min-w-0 flex-col leading-none">
      <span className="text-[17px] font-semibold tracking-[-0.035em] text-slate-900 dark:text-slate-50">
        HireFlow
      </span>
      {subtitle && (
        <span className="mt-1 truncate text-[11px] font-medium tracking-[0.01em] text-slate-500 dark:text-slate-400">
          {subtitle}
        </span>
      )}
    </span>
  </span>
);
