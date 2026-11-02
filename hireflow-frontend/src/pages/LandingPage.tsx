import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import type { LucideIcon } from 'lucide-react';
import {
  ArrowRight,
  ArrowUpRight,
  Check,
  ChevronRight,
  CircleDot,
  FileCheck2,
  Fingerprint,
  Inbox,
  Layers,
  LockKeyhole,
  Mail,
  Menu,
  Moon,
  Route,
  ScanSearch,
  ScrollText,
  ShieldCheck,
  SlidersHorizontal,
  Sun,
  Upload,
  X,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../context/ThemeContext';
import { BrandMark } from '../components/brand/BrandMark';

const navLinks = [
  { label: 'Platform', href: '#platform' },
  { label: 'Workflow', href: '#workflow' },
  { label: 'Security', href: '#security' },
];

const proofPoints = [
  { icon: Inbox, label: 'Mailbox + webhook intake' },
  { icon: Fingerprint, label: 'Identity resolution' },
  { icon: Route, label: 'Rules-based routing' },
  { icon: ScanSearch, label: 'Explainable matching' },
];

const principles = [
  {
    number: '01',
    title: 'Capture every channel',
    body: 'OAuth, IMAP, direct upload, extension intake, and signed forwarding webhooks share one ingestion contract.',
  },
  {
    number: '02',
    title: 'Resolve with evidence',
    body: 'Deduplicate on content and identity, keep source provenance, and escalate ambiguous matches instead of guessing.',
  },
  {
    number: '03',
    title: 'Route with intent',
    body: 'Rules decide what enters the pipeline, which requisition it belongs to, and when a human should review.',
  },
];

type Feature = {
  title: string;
  body: string;
  icon: LucideIcon;
  className: string;
  span: string;
  tone?: 'pine' | 'paper';
  detail?: React.ReactNode;
};

const features: Feature[] = [
  {
    title: 'Intake that meets candidates where they are',
    body: 'Gmail, Microsoft 365, IMAP, direct upload, and forwarding webhooks. Attachments are content-verified, size-bounded, and stored behind one storage contract.',
    icon: Inbox,
    span: 'md:col-span-4',
    className: '',
    tone: 'paper',
    detail: (
      <div className="mt-6 flex flex-wrap gap-2">
        {['Gmail', 'Microsoft 365', 'IMAP', 'Webhook', 'Direct upload'].map((channel) => (
          <span
            key={channel}
            className="inline-flex items-center gap-1.5 rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-[11px] font-medium text-slate-600 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-300"
          >
            <span className="h-1 w-1 rounded-full bg-teal-600 dark:bg-teal-400" />
            {channel}
          </span>
        ))}
      </div>
    ),
  },
  {
    title: 'A review queue, not a black box',
    body: 'Ambiguous identities and extraction conflicts surface with reason codes, context, and a resolution path.',
    icon: ShieldCheck,
    span: 'md:col-span-2',
    className: 'bg-teal-700 text-paper-50 border-teal-700 dark:bg-teal-800 dark:border-teal-800',
    tone: 'pine',
    detail: (
      <div className="mt-6 space-y-2">
        <div className="flex items-center justify-between rounded-lg border border-white/15 bg-white/5 px-3 py-2">
          <span className="text-[11px] text-paper-50/80">Ambiguous identity</span>
          <span className="rounded-full bg-clay-300/20 px-2 py-0.5 text-[10px] font-semibold text-clay-100">
            2 pending
          </span>
        </div>
        <div className="flex items-center justify-between rounded-lg border border-white/15 bg-white/5 px-3 py-2">
          <span className="text-[11px] text-paper-50/80">Extraction conflict</span>
          <span className="rounded-full bg-white/10 px-2 py-0.5 text-[10px] font-semibold text-paper-50/80">
            Review
          </span>
        </div>
      </div>
    ),
  },
  {
    title: 'Identity resolution with provenance',
    body: 'Living candidate profiles merge safely. Original sender claims stay separate from authenticated mailbox identity.',
    icon: Fingerprint,
    span: 'md:col-span-2',
    className: '',
    tone: 'paper',
  },
  {
    title: 'Rules that explain themselves',
    body: 'Sender, subject, body, attachment, and job conditions evaluate in a visible order — with a preview before anything goes live.',
    icon: SlidersHorizontal,
    span: 'md:col-span-2',
    className: '',
    tone: 'paper',
  },
  {
    title: 'Matching you can defend',
    body: 'Skill, experience, and role scores stay explainable, with matched and missing skills visible to the recruiter.',
    icon: ScanSearch,
    span: 'md:col-span-2',
    className: '',
    tone: 'paper',
  },
  {
    title: 'An audit trail from first touch',
    body: 'Every inbound message carries its processing lifecycle, rule evaluation, attachment outcomes, and failure diagnostics — so recovery is a procedure, not a guess.',
    icon: ScrollText,
    span: 'md:col-span-3',
    className: '',
    tone: 'paper',
  },
  {
    title: 'Tenant isolation by default',
    body: 'Organization-scoped credentials, encrypted secrets, and authorization enforced server-side. No tenant identifiers trusted from the browser.',
    icon: LockKeyhole,
    span: 'md:col-span-3',
    className: '',
    tone: 'paper',
  },
];

const securityFacts = [
  'Per-organization webhook credentials',
  'HMAC-SHA256 v2 request signatures',
  'AES-256-GCM encryption at rest',
  'Durable nonce and replay protection',
  'Strict multipart and size limits',
  'Explicit environment profiles',
];

const workflow = [
  {
    number: '01',
    title: 'Capture',
    body: 'Connect Gmail, Microsoft 365, IMAP, direct upload, or a signed forwarding webhook. Every attachment is verified, bounded, and recorded.',
    icon: Mail,
  },
  {
    number: '02',
    title: 'Resolve',
    body: 'Deduplicate against existing candidates, preserve provenance, and surface ambiguous identities to a human review queue.',
    icon: Fingerprint,
  },
  {
    number: '03',
    title: 'Route',
    body: 'Apply recruitment rules, map candidates to requisitions, and move applications through a transparent, auditable pipeline.',
    icon: Route,
  },
];

const DetailRow: React.FC<{ icon: LucideIcon; label: string; value: string; tone?: 'pine' | 'clay' }> = ({
  icon: Icon,
  label,
  value,
  tone = 'pine',
}) => (
  <div className="flex items-center gap-3 rounded-xl border border-slate-200 bg-slate-50/70 px-3 py-2.5 dark:border-slate-800 dark:bg-slate-950/50">
    <span
      className={`flex h-7 w-7 items-center justify-center rounded-lg ${
        tone === 'clay'
          ? 'bg-clay-50 text-clay-600 dark:bg-clay-950/60 dark:text-clay-300'
          : 'bg-teal-50 text-teal-700 dark:bg-teal-950/60 dark:text-teal-300'
      }`}
    >
      <Icon className="h-3.5 w-3.5" />
    </span>
    <span className="min-w-0">
      <span className="block text-[10px] font-medium uppercase tracking-[0.12em] text-slate-500 dark:text-slate-400">
        {label}
      </span>
      <span className="block truncate text-[12px] font-semibold text-slate-800 dark:text-slate-100">
        {value}
      </span>
    </span>
  </div>
);

const ProductPreview: React.FC = () => (
  <div className="relative mx-auto w-full max-w-[560px]" aria-hidden="true">
    <div className="absolute -left-12 top-12 h-64 w-64 rounded-full bg-clay-200/50 blur-3xl dark:bg-clay-900/20" />
    <div className="absolute -right-10 bottom-2 h-72 w-72 rounded-full bg-teal-200/50 blur-3xl dark:bg-teal-900/25" />

    <div className="relative overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xl dark:border-slate-800 dark:bg-slate-900">
      <div className="flex items-center gap-2 border-b border-slate-200 bg-slate-50/80 px-4 py-3 dark:border-slate-800 dark:bg-slate-950/60">
        <span className="h-2 w-2 rounded-full bg-slate-300 dark:bg-slate-700" />
        <span className="h-2 w-2 rounded-full bg-slate-300 dark:bg-slate-700" />
        <span className="h-2 w-2 rounded-full bg-slate-300 dark:bg-slate-700" />
        <span className="ml-2 truncate text-[10px] font-medium tracking-wide text-slate-500 dark:text-slate-400">
          app.hireflow / operations
        </span>
        <span className="ml-auto inline-flex items-center gap-1.5 rounded-full border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-[9px] font-semibold text-emerald-700 dark:border-emerald-800/60 dark:bg-emerald-950/50 dark:text-emerald-300">
          <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
          Live
        </span>
      </div>

      <div className="grid grid-cols-[52px_1fr]">
        <div className="flex flex-col items-center gap-4 border-r border-slate-200 bg-slate-50/50 px-2 py-4 dark:border-slate-800 dark:bg-slate-950/30">
          <BrandMark size="sm" />
          <div className="flex flex-col items-center gap-3 text-slate-400 dark:text-slate-500">
            <Inbox className="h-4 w-4 text-teal-700 dark:text-teal-400" />
            <Fingerprint className="h-4 w-4" />
            <Route className="h-4 w-4" />
            <ScrollText className="h-4 w-4" />
          </div>
        </div>

        <div className="min-w-0 p-4 sm:p-5">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-[9px] font-semibold uppercase tracking-[0.16em] text-slate-500 dark:text-slate-400">
                Monday, 3 November
              </p>
              <p className="mt-1 text-sm font-semibold tracking-tight text-slate-900 dark:text-slate-50">
                Recruitment operations
              </p>
            </div>
            <span className="inline-flex items-center gap-1.5 rounded-full border border-teal-200 bg-teal-50 px-2 py-1 text-[9px] font-semibold text-teal-700 dark:border-teal-800/60 dark:bg-teal-950/50 dark:text-teal-300">
              <span className="h-1.5 w-1.5 rounded-full bg-teal-600 dark:bg-teal-400" />
              Queue clear
            </span>
          </div>

          <div className="mt-4 grid grid-cols-3 gap-2">
            <div className="rounded-xl border border-slate-200 bg-white p-2.5 dark:border-slate-800 dark:bg-slate-900">
              <p className="text-[9px] font-medium text-slate-500 dark:text-slate-400">Candidates</p>
              <p className="mt-1 font-mono text-base font-semibold text-slate-900 dark:text-slate-50">1,284</p>
            </div>
            <div className="rounded-xl border border-slate-200 bg-white p-2.5 dark:border-slate-800 dark:bg-slate-900">
              <p className="text-[9px] font-medium text-slate-500 dark:text-slate-400">In queue</p>
              <p className="mt-1 font-mono text-base font-semibold text-slate-900 dark:text-slate-50">3</p>
            </div>
            <div className="rounded-xl border border-slate-200 bg-white p-2.5 dark:border-slate-800 dark:bg-slate-900">
              <p className="text-[9px] font-medium text-slate-500 dark:text-slate-400">Inboxes</p>
              <p className="mt-1 font-mono text-base font-semibold text-slate-900 dark:text-slate-50">4</p>
            </div>
          </div>

          <div className="mt-4 overflow-hidden rounded-xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
            <div className="flex items-center justify-between border-b border-slate-200 px-3 py-2 dark:border-slate-800">
              <span className="text-[10px] font-semibold text-slate-700 dark:text-slate-200">Recent intake</span>
              <span className="text-[9px] font-medium text-slate-400">View all</span>
            </div>
            {[
              ['Senior Backend Engineer', 'Mailbox', 'Ready', 'emerald'],
              ['Product Designer', 'Webhook', 'Review', 'amber'],
              ['Data Analyst', 'Upload', 'Matched', 'teal'],
            ].map(([role, source, status, tone]) => (
              <div
                key={role}
                className="flex items-center gap-2 border-b border-slate-100 px-3 py-2 last:border-0 dark:border-slate-800/70"
              >
                <span className="flex h-5 w-5 items-center justify-center rounded-md bg-slate-100 text-[8px] font-bold text-slate-500 dark:bg-slate-800 dark:text-slate-300">
                  {role.charAt(0)}
                </span>
                <span className="min-w-0 flex-1 truncate text-[10px] font-medium text-slate-700 dark:text-slate-200">
                  {role}
                </span>
                <span className="hidden text-[9px] text-slate-400 sm:inline">{source}</span>
                <span
                  className={`rounded-full px-1.5 py-0.5 text-[8px] font-semibold ${
                    tone === 'emerald'
                      ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-300'
                      : tone === 'amber'
                      ? 'bg-amber-50 text-amber-700 dark:bg-amber-950/60 dark:text-amber-300'
                      : 'bg-teal-50 text-teal-700 dark:bg-teal-950/60 dark:text-teal-300'
                  }`}
                >
                  {status}
                </span>
              </div>
            ))}
          </div>

          <div className="mt-3 flex items-center gap-2">
            <span className="text-[9px] font-medium text-slate-500 dark:text-slate-400">Identity resolution</span>
            <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
              <div className="h-full w-[78%] rounded-full bg-teal-600 dark:bg-teal-400" />
            </div>
            <span className="font-mono text-[9px] font-semibold text-teal-700 dark:text-teal-300">78%</span>
          </div>
        </div>
      </div>
    </div>

    <div className="absolute -bottom-7 -left-4 hidden w-48 rounded-xl border border-slate-200 bg-white p-3 shadow-lg dark:border-slate-800 dark:bg-slate-900 sm:block">
      <p className="text-[9px] font-semibold uppercase tracking-[0.16em] text-slate-500 dark:text-slate-400">
        Review queue
      </p>
      <div className="mt-2 flex items-end justify-between">
        <span className="font-mono text-xl font-semibold text-slate-900 dark:text-slate-50">2</span>
        <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[9px] font-semibold text-amber-700 dark:bg-amber-950/60 dark:text-amber-300">
          Needs eyes
        </span>
      </div>
      <p className="mt-1 text-[10px] text-slate-500 dark:text-slate-400">Ambiguous identity</p>
    </div>

    <div className="absolute -right-3 -top-4 hidden items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-2 shadow-lg dark:border-slate-800 dark:bg-slate-900 md:flex">
      <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-clay-50 text-clay-600 dark:bg-clay-950/60 dark:text-clay-300">
        <FileCheck2 className="h-3.5 w-3.5" />
      </span>
      <span>
        <span className="block text-[9px] font-medium text-slate-500 dark:text-slate-400">Attachment verified</span>
        <span className="block text-[10px] font-semibold text-slate-800 dark:text-slate-100">resume.pdf · 412 KB</span>
      </span>
    </div>
  </div>
);

const LandingHeader: React.FC<{ signedIn: boolean }> = ({ signedIn }) => {
  const [mobileOpen, setMobileOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const { theme, toggleTheme } = useTheme();

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 12);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  return (
    <header
      className={`sticky top-0 z-50 border-b transition-colors duration-300 ${
        scrolled
          ? 'border-slate-200/80 bg-[#F7F4EE]/90 backdrop-blur-xl dark:border-slate-800/80 dark:bg-[#12100E]/90'
          : 'border-transparent bg-transparent'
      }`}
    >
      <div className="mx-auto flex h-[72px] w-full max-w-[1240px] items-center gap-6 px-5 sm:px-8">
        <Link to="/" className="group flex items-center gap-3" aria-label="HireFlow home">
          <BrandMark size="md" className="transition-transform duration-300 group-hover:-rotate-3" />
          <span className="text-[17px] font-semibold tracking-[-0.035em] text-slate-900 dark:text-slate-50">
            HireFlow
          </span>
        </Link>

        <nav className="ml-4 hidden items-center gap-7 md:flex" aria-label="Primary">
          {navLinks.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className="text-[13px] font-medium text-slate-600 transition-colors hover:text-slate-950 dark:text-slate-400 dark:hover:text-slate-100"
            >
              {link.label}
            </a>
          ))}
          <Link
            to="/docs"
            className="text-[13px] font-medium text-slate-600 transition-colors hover:text-slate-950 dark:text-slate-400 dark:hover:text-slate-100"
          >
            Documentation
          </Link>
        </nav>

        <div className="ml-auto hidden items-center gap-2 sm:flex">
          <button
            type="button"
            onClick={toggleTheme}
            className="flex h-9 w-9 items-center justify-center rounded-lg text-slate-500 transition-colors hover:bg-slate-900/5 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-white/5 dark:hover:text-slate-100"
            aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          >
            {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          </button>
          <Link
            to={signedIn ? '/login?reauth=1' : '/login'}
            className="inline-flex h-9 items-center gap-2 rounded-lg px-3 text-[13px] font-semibold text-slate-700 transition-colors hover:bg-slate-900/5 hover:text-slate-950 dark:text-slate-300 dark:hover:bg-white/5 dark:hover:text-white"
          >
            Sign in
          </Link>
          <Link
            to={signedIn ? '/dashboard' : '/login'}
            className="inline-flex h-9 items-center gap-2 rounded-lg bg-teal-700 px-3.5 text-[13px] font-semibold text-paper-50 shadow-sm transition-all hover:-translate-y-px hover:bg-teal-800 hover:shadow-md"
          >
            {signedIn ? 'Open workspace' : 'Explore platform'}
            <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </div>

        <button
          type="button"
          onClick={() => setMobileOpen((open) => !open)}
          className="ml-auto flex h-9 w-9 items-center justify-center rounded-lg text-slate-600 transition-colors hover:bg-slate-900/5 dark:text-slate-300 dark:hover:bg-white/5 sm:hidden"
          aria-label={mobileOpen ? 'Close navigation menu' : 'Open navigation menu'}
          aria-expanded={mobileOpen}
        >
          {mobileOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
        </button>
      </div>

      {mobileOpen && (
        <div className="border-t border-slate-200 bg-[#F7F4EE] px-5 pb-6 pt-4 dark:border-slate-800 dark:bg-[#12100E] sm:hidden">
          <nav className="flex flex-col" aria-label="Mobile">
            {navLinks.map((link) => (
              <a
                key={link.href}
                href={link.href}
                onClick={() => setMobileOpen(false)}
                className="flex items-center justify-between border-b border-slate-200 py-3 text-sm font-medium text-slate-700 dark:border-slate-800 dark:text-slate-200"
              >
                {link.label}
                <ChevronRight className="h-4 w-4 text-slate-400" />
              </a>
            ))}
            <Link
              to="/docs"
              onClick={() => setMobileOpen(false)}
              className="flex items-center justify-between border-b border-slate-200 py-3 text-sm font-medium text-slate-700 dark:border-slate-800 dark:text-slate-200"
            >
              Documentation
              <ChevronRight className="h-4 w-4 text-slate-400" />
            </Link>
          </nav>
          <div className="mt-5 grid grid-cols-2 gap-2">
            <Link
              to={signedIn ? '/login?reauth=1' : '/login'}
              onClick={() => setMobileOpen(false)}
              className="inline-flex h-10 items-center justify-center rounded-lg border border-slate-300 bg-white text-[13px] font-semibold text-slate-800 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            >
              Sign in
            </Link>
            {signedIn ? (
              <Link
                to="/dashboard"
                onClick={() => setMobileOpen(false)}
                className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-teal-700 text-[13px] font-semibold text-paper-50"
              >
                Open workspace
                <ArrowRight className="h-3.5 w-3.5" />
              </Link>
            ) : (
              <a
                href="#platform"
                onClick={() => setMobileOpen(false)}
                className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-teal-700 text-[13px] font-semibold text-paper-50"
              >
                Explore platform
                <ArrowRight className="h-3.5 w-3.5" />
              </a>
            )}
          </div>
          <button
            type="button"
            onClick={toggleTheme}
            className="mt-4 inline-flex items-center gap-2 text-[13px] font-medium text-slate-600 dark:text-slate-400"
          >
            {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            {theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          </button>
        </div>
      )}
    </header>
  );
};

const SectionHeading: React.FC<{
  eyebrow: string;
  title: React.ReactNode;
  body?: string;
  align?: 'left' | 'center';
}> = ({ eyebrow, title, body, align = 'left' }) => (
  <div className={align === 'center' ? 'mx-auto max-w-3xl text-center' : 'max-w-2xl'}>
    <p className="eyebrow">{eyebrow}</p>
    <h2 className="display mt-4 text-[clamp(2.1rem,4.2vw,3.6rem)] leading-[1.02] text-slate-950 dark:text-slate-50">
      {title}
    </h2>
    {body && (
      <p className="mt-5 max-w-2xl text-[15px] leading-7 text-slate-600 dark:text-slate-400">{body}</p>
    )}
  </div>
);

export const LandingPage: React.FC = () => {
  const { user } = useAuth();
  const signedIn = Boolean(user);
  const authHref = signedIn ? '/dashboard' : '/login';

  return (
    <div className="min-h-screen overflow-x-clip bg-[#F7F4EE] text-slate-900 dark:bg-[#12100E] dark:text-slate-100">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-[60] focus:rounded-lg focus:bg-white focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:shadow-lg dark:focus:bg-slate-900"
      >
        Skip to content
      </a>

      <LandingHeader signedIn={signedIn} />

      <main id="main">
        {/* ── Hero ─────────────────────────────────────────────── */}
        <section className="relative">
          <div
            className="pointer-events-none absolute inset-x-0 top-0 h-[680px] dot-grid opacity-60 dark:opacity-40"
            style={{ maskImage: 'linear-gradient(to bottom, black, transparent 82%)', WebkitMaskImage: 'linear-gradient(to bottom, black, transparent 82%)' }}
          />
          <div className="relative mx-auto grid w-full max-w-[1240px] items-center gap-14 px-5 pb-20 pt-14 sm:px-8 lg:grid-cols-[1.06fr_0.94fr] lg:gap-16 lg:pb-28 lg:pt-20">
            <div className="animate-fade-up">
              <p className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white/70 px-3 py-1.5 text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-600 shadow-xs backdrop-blur dark:border-slate-800 dark:bg-slate-900/60 dark:text-slate-300">
                <span className="h-1.5 w-1.5 rounded-full bg-clay-500" />
                Candidate intake, resolved
              </p>

              <h1 className="display mt-6 text-[clamp(3.15rem,6.6vw,5.85rem)] leading-[0.92] text-slate-950 dark:text-slate-50">
                Every resume.
                <br />
                Every source.
                <br />
                One <em className="text-clay-600 dark:text-clay-300">accountable</em> pipeline.
              </h1>

              <p className="mt-7 max-w-xl text-[15px] leading-7 text-slate-600 dark:text-slate-400 sm:text-base">
                HireFlow connects your hiring inboxes, resolves candidate identity, and routes every
                application into a single auditable recruitment pipeline — so nothing arrives twice,
                disappears quietly, or ends up in a spreadsheet.
              </p>

              <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center">
                <Link
                  to={authHref}
                  className="inline-flex h-12 items-center justify-center gap-2 rounded-lg bg-teal-700 px-5 text-sm font-semibold text-paper-50 shadow-sm transition-all hover:-translate-y-0.5 hover:bg-teal-800 hover:shadow-md"
                >
                  {signedIn ? 'Open your workspace' : 'Sign in to workspace'}
                  <ArrowRight className="h-4 w-4" />
                </Link>
                <a
                  href="#workflow"
                  className="inline-flex h-12 items-center justify-center gap-2 rounded-lg border border-slate-300 bg-white/70 px-5 text-sm font-semibold text-slate-800 backdrop-blur transition-all hover:border-slate-400 hover:bg-white dark:border-slate-700 dark:bg-slate-900/60 dark:text-slate-100 dark:hover:border-slate-600 dark:hover:bg-slate-900"
                >
                  See the workflow
                  <ChevronRight className="h-4 w-4" />
                </a>
              </div>

              <p className="mt-5 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[12px] text-slate-500 dark:text-slate-400">
                <span className="inline-flex items-center gap-1.5">
                  <Check className="h-3.5 w-3.5 text-teal-600 dark:text-teal-400" />
                  Human review where it matters
                </span>
                <span className="inline-flex items-center gap-1.5">
                  <Check className="h-3.5 w-3.5 text-teal-600 dark:text-teal-400" />
                  Provenance preserved
                </span>
              </p>
            </div>

            <div className="animate-fade-up [animation-delay:120ms]">
              <ProductPreview />
            </div>
          </div>
        </section>

        {/* ── Proof strip ──────────────────────────────────────── */}
        <section className="border-y border-slate-200 bg-white/55 dark:border-slate-800 dark:bg-slate-950/25">
          <div className="mx-auto grid w-full max-w-[1240px] grid-cols-2 gap-px px-5 py-0 sm:px-8 lg:grid-cols-4">
            {proofPoints.map(({ icon: Icon, label }, index) => (
              <div
                key={label}
                className={`flex items-center gap-3 py-5 ${
                  index !== 0 ? 'lg:border-l lg:border-slate-200 lg:pl-6 dark:lg:border-slate-800' : ''
                }`}
              >
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-teal-700 shadow-xs dark:border-slate-800 dark:bg-slate-900 dark:text-teal-300">
                  <Icon className="h-4 w-4" />
                </span>
                <span className="text-[12px] font-semibold text-slate-700 dark:text-slate-200">{label}</span>
              </div>
            ))}
          </div>
        </section>

        {/* ── Platform intro ───────────────────────────────────── */}
        <section id="platform" className="scroll-mt-24 py-20 sm:py-28">
          <div className="mx-auto grid w-full max-w-[1240px] gap-12 px-5 sm:px-8 lg:grid-cols-[0.9fr_1.1fr] lg:gap-20">
            <div>
              <p className="eyebrow">One system of record</p>
              <h2 className="display mt-4 text-[clamp(2.1rem,4.2vw,3.6rem)] leading-[1.02] text-slate-950 dark:text-slate-50">
                Hiring data arrives in fragments. <span className="text-clay-600 dark:text-clay-300">HireFlow makes it whole.</span>
              </h2>
            </div>
            <div className="lg:pt-3">
              <p className="text-[15px] leading-7 text-slate-600 dark:text-slate-400">
                A candidate can reach you through a careers inbox, a forwarded email, a direct upload,
                or a browser extension. Each path carries different metadata, duplicates, and risk.
                HireFlow normalizes intake without flattening the context that makes a candidate real.
              </p>
              <div className="mt-8 space-y-6">
                {principles.map((principle) => (
                  <div key={principle.number} className="flex gap-4">
                    <span className="mt-0.5 font-mono text-[11px] font-semibold text-clay-600 dark:text-clay-300">
                      {principle.number}
                    </span>
                    <div>
                      <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-50">
                        {principle.title}
                      </h3>
                      <p className="mt-1 text-[13px] leading-6 text-slate-600 dark:text-slate-400">
                        {principle.body}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </section>

        {/* ── Feature bento ────────────────────────────────────── */}
        <section className="pb-20 sm:pb-28">
          <div className="mx-auto w-full max-w-[1240px] px-5 sm:px-8">
            <SectionHeading
              eyebrow="Built for the whole intake path"
              title={<>The operational layer between <em className="text-clay-600 dark:text-clay-300">inbox</em> and <em className="text-clay-600 dark:text-clay-300">interview</em>.</>}
              body="Not another resume database. A dependable intake system with human checkpoints, explainable decisions, and a record of what happened."
            />

            <div className="mt-12 grid gap-3 md:grid-cols-6">
              {features.map(({ title, body, icon: Icon, span, className, tone = 'paper', detail }) => (
                <article
                  key={title}
                  className={`group flex flex-col rounded-2xl border p-6 shadow-xs transition-all duration-300 ease-out-expo hover:-translate-y-0.5 hover:shadow-md ${span} ${
                    tone === 'pine'
                      ? className
                      : `border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900 ${className}`
                  }`}
                >
                  <span
                    className={`flex h-9 w-9 items-center justify-center rounded-lg ${
                      tone === 'pine'
                        ? 'bg-white/10 text-paper-50'
                        : 'border border-slate-200 bg-slate-50 text-teal-700 dark:border-slate-700 dark:bg-slate-800/60 dark:text-teal-300'
                    }`}
                  >
                    <Icon className="h-4 w-4" />
                  </span>
                  <h3
                    className={`mt-5 text-[15px] font-semibold tracking-[-0.01em] ${
                      tone === 'pine' ? 'text-paper-50' : 'text-slate-900 dark:text-slate-50'
                    }`}
                  >
                    {title}
                  </h3>
                  <p
                    className={`mt-2 text-[13px] leading-6 ${
                      tone === 'pine' ? 'text-paper-50/70' : 'text-slate-600 dark:text-slate-400'
                    }`}
                  >
                    {body}
                  </p>
                  {detail}
                </article>
              ))}
            </div>
          </div>
        </section>

        {/* ── Workflow ─────────────────────────────────────────── */}
        <section id="workflow" className="scroll-mt-24 border-y border-slate-200 bg-white/55 py-20 dark:border-slate-800 dark:bg-slate-950/25 sm:py-28">
          <div className="mx-auto w-full max-w-[1240px] px-5 sm:px-8">
            <SectionHeading
              eyebrow="How it works"
              title={<>From crowded inbox to <em className="text-clay-600 dark:text-clay-300">clear decision</em>.</>}
              body="Three deliberate steps. No silent drops, no mystery state, no spreadsheet archaeology."
            />

            <div className="mt-14 grid gap-10 md:grid-cols-3 md:gap-0">
              {workflow.map((step, index) => {
                const Icon = step.icon;
                return (
                  <div key={step.number} className="relative md:px-8 md:first:pl-0 md:last:pr-0">
                    {index < workflow.length - 1 && (
                      <div className="absolute left-[calc(50%+2.5rem)] right-0 top-8 hidden h-px bg-slate-200 dark:bg-slate-800 md:block" />
                    )}
                    <div className="flex items-center gap-3">
                      <span className="flex h-10 w-10 items-center justify-center rounded-full border border-teal-200 bg-teal-50 text-teal-700 dark:border-teal-800/60 dark:bg-teal-950/50 dark:text-teal-300">
                        <Icon className="h-4 w-4" />
                      </span>
                      <span className="font-mono text-[11px] font-semibold text-slate-400">{step.number}</span>
                    </div>
                    <h3 className="mt-5 text-lg font-semibold tracking-[-0.015em] text-slate-950 dark:text-slate-50">
                      {step.title}
                    </h3>
                    <p className="mt-2 max-w-sm text-[13px] leading-6 text-slate-600 dark:text-slate-400">
                      {step.body}
                    </p>
                  </div>
                );
              })}
            </div>
          </div>
        </section>

        {/* ── Security ─────────────────────────────────────────── */}
        <section id="security" className="scroll-mt-24 py-20 sm:py-28">
          <div className="mx-auto w-full max-w-[1240px] px-5 sm:px-8">
            <div className="relative overflow-hidden rounded-3xl bg-teal-900 px-6 py-10 text-paper-50 shadow-xl dark:bg-teal-950 sm:px-10 sm:py-14 lg:px-14">
              <div className="pointer-events-none absolute -right-24 -top-24 h-80 w-80 rounded-full border border-white/10" />
              <div className="pointer-events-none absolute -bottom-32 -left-16 h-96 w-96 rounded-full border border-white/[0.07]" />
              <div className="relative grid gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:gap-20">
                <div>
                  <p className="text-[11px] font-semibold uppercase tracking-[0.18em] text-teal-200">
                    Security &amp; control
                  </p>
                  <h2 className="display mt-4 text-[clamp(2.1rem,4.2vw,3.5rem)] leading-[1.02] text-paper-50">
                    Security is not a <em className="text-clay-200">settings page</em>.
                  </h2>
                  <p className="mt-5 max-w-md text-[14px] leading-7 text-paper-50/65">
                    Candidate data is sensitive by definition. HireFlow treats intake as a security
                    boundary: authenticated senders, bounded payloads, tenant-scoped queries, and
                    secrets encrypted at rest.
                  </p>
                  <Link
                    to="/docs"
                    className="mt-7 inline-flex items-center gap-1.5 text-[13px] font-semibold text-paper-50 underline decoration-white/25 underline-offset-4 transition-colors hover:decoration-clay-300"
                  >
                    Read the security contract
                    <ArrowUpRight className="h-3.5 w-3.5" />
                  </Link>
                </div>

                <div className="grid gap-x-8 gap-y-4 sm:grid-cols-2">
                  {securityFacts.map((fact) => (
                    <div key={fact} className="flex items-start gap-3 border-t border-white/10 pt-4">
                      <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-clay-300/15 text-clay-200">
                        <Check className="h-3 w-3" />
                      </span>
                      <span className="text-[13px] font-medium leading-6 text-paper-50/85">{fact}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* ── Final CTA ────────────────────────────────────────── */}
        <section className="pb-20 sm:pb-28">
          <div className="mx-auto w-full max-w-[1240px] px-5 sm:px-8">
            <div className="relative overflow-hidden rounded-3xl border border-slate-200 bg-[#FCFAF6] px-6 py-12 dark:border-slate-800 dark:bg-slate-900 sm:px-12 sm:py-16">
              <div className="pointer-events-none absolute -right-16 -top-16 h-64 w-64 rounded-full border border-clay-200/70 dark:border-clay-900/50" />
              <div className="relative grid items-end gap-8 lg:grid-cols-[1fr_auto]">
                <div>
                  <p className="eyebrow">Start with one source</p>
                  <h2 className="display mt-4 max-w-2xl text-[clamp(2.3rem,4.6vw,4rem)] leading-[0.98] text-slate-950 dark:text-slate-50">
                    Bring order to the top of your <em className="text-clay-600 dark:text-clay-300">funnel</em>.
                  </h2>
                  <p className="mt-4 max-w-xl text-[14px] leading-7 text-slate-600 dark:text-slate-400">
                    Connect a mailbox or a signed webhook. Keep the audit trail. Give recruiters back
                    the part of the work that actually needs a human.
                  </p>
                </div>
                <div className="flex flex-col gap-2 sm:flex-row lg:flex-col">
                  <Link
                    to={authHref}
                    className="inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-teal-700 px-5 text-[13px] font-semibold text-paper-50 shadow-sm transition-all hover:-translate-y-0.5 hover:bg-teal-800 hover:shadow-md"
                  >
                    {signedIn ? 'Open workspace' : 'Sign in to workspace'}
                    <ArrowRight className="h-4 w-4" />
                  </Link>
                  <a
                    href="#workflow"
                    className="inline-flex h-11 items-center justify-center gap-2 rounded-lg border border-slate-300 bg-white px-5 text-[13px] font-semibold text-slate-800 transition-colors hover:border-slate-400 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
                  >
                    Revisit the workflow
                  </a>
                </div>
              </div>
            </div>
          </div>
        </section>
      </main>

      {/* ── Footer ─────────────────────────────────────────────── */}
      <footer className="border-t border-slate-200 bg-white/40 dark:border-slate-800 dark:bg-slate-950/20">
        <div className="mx-auto w-full max-w-[1240px] px-5 py-12 sm:px-8">
          <div className="grid gap-10 sm:grid-cols-2 lg:grid-cols-[1.5fr_1fr_1fr_1fr]">
            <div>
              <Link to="/" className="inline-flex items-center gap-3" aria-label="HireFlow home">
                <BrandMark size="md" />
                <span className="text-[17px] font-semibold tracking-[-0.035em] text-slate-900 dark:text-slate-50">
                  HireFlow
                </span>
              </Link>
              <p className="mt-4 max-w-xs text-[13px] leading-6 text-slate-500 dark:text-slate-400">
                Candidate intake, identity resolution, and explainable matching for recruitment teams
                that need fewer spreadsheets and more signal.
              </p>
            </div>

            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-500 dark:text-slate-400">
                Product
              </p>
              <ul className="mt-3 space-y-2">
                {[
                  ['Platform', '#platform'],
                  ['Workflow', '#workflow'],
                  ['Security', '#security'],
                ].map(([label, href]) => (
                  <li key={label}>
                    <a
                      href={href}
                      className="text-[13px] text-slate-600 transition-colors hover:text-slate-950 dark:text-slate-400 dark:hover:text-slate-100"
                    >
                      {label}
                    </a>
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-500 dark:text-slate-400">
                Workspace
              </p>
              <ul className="mt-3 space-y-2">
                <li>
                  <Link
                    to={signedIn ? '/login?reauth=1' : '/login'}
                    className="text-[13px] text-slate-600 transition-colors hover:text-slate-950 dark:text-slate-400 dark:hover:text-slate-100"
                  >
                    Sign in
                  </Link>
                </li>
                {signedIn && (
                  <li>
                    <Link
                      to="/dashboard"
                      className="text-[13px] text-slate-600 transition-colors hover:text-slate-950 dark:text-slate-400 dark:hover:text-slate-100"
                    >
                      Open workspace
                    </Link>
                  </li>
                )}
                <li>
                  <Link
                    to="/docs"
                    className="text-[13px] text-slate-600 transition-colors hover:text-slate-950 dark:text-slate-400 dark:hover:text-slate-100"
                  >
                    Documentation
                  </Link>
                </li>
              </ul>
            </div>

            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-500 dark:text-slate-400">
                Principles
              </p>
              <ul className="mt-3 space-y-2 text-[13px] text-slate-600 dark:text-slate-400">
                <li className="flex items-center gap-2">
                  <CircleDot className="h-3 w-3 text-teal-600 dark:text-teal-400" />
                  Tenant isolated
                </li>
                <li className="flex items-center gap-2">
                  <CircleDot className="h-3 w-3 text-teal-600 dark:text-teal-400" />
                  Human review
                </li>
                <li className="flex items-center gap-2">
                  <CircleDot className="h-3 w-3 text-teal-600 dark:text-teal-400" />
                  Auditable by default
                </li>
              </ul>
            </div>
          </div>

          <div className="mt-12 flex flex-col gap-3 border-t border-slate-200 pt-6 text-[11px] text-slate-500 dark:border-slate-800 dark:text-slate-400 sm:flex-row sm:items-center sm:justify-between">
            <span>© {new Date().getFullYear()} HireFlow. Built for accountable hiring.</span>
            <span className="inline-flex items-center gap-1.5">
              <Layers className="h-3.5 w-3.5" />
              Modular monolith · PostgreSQL · Java 21
            </span>
          </div>
        </div>
      </footer>
    </div>
  );
};

export default LandingPage;
