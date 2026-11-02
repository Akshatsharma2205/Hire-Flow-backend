import React, { useState, useEffect } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import {
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  Eye,
  EyeOff,
  Fingerprint,
  Inbox,
  Lock,
  Mail,
  ScanSearch,
  ShieldCheck,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { Button } from '../components/common/Button';
import { BrandMark } from '../components/brand/BrandMark';

const assurances = [
  {
    icon: Inbox,
    title: 'One intake contract',
    body: 'Mailbox, upload, extension, and signed webhook traffic all land in the same auditable queue.',
  },
  {
    icon: Fingerprint,
    title: 'Identity resolved, not guessed',
    body: 'Duplicates merge on evidence. Ambiguity becomes a review case instead of a silent overwrite.',
  },
  {
    icon: ScanSearch,
    title: 'Decisions you can explain',
    body: 'Rules and matching scores keep their reasoning attached, from first touch to final stage.',
  },
];

export const LoginPage: React.FC = () => {
  const { login, quickDemoLogin, user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const isReauth = new URLSearchParams(location.search).get('reauth') === '1';

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (user && !isReauth) {
      navigate('/dashboard', { replace: true });
    }
  }, [user, isReauth, navigate]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email || !password) {
      setError('Please enter both email and password.');
      return;
    }
    setError(null);
    setIsSubmitting(true);
    try {
      await login(email, password);
      navigate('/dashboard', { replace: true });
    } catch (err: any) {
      setError(err.message || 'Invalid credentials. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleDemoLogin = async () => {
    setError(null);
    setIsSubmitting(true);
    try {
      await quickDemoLogin();
      navigate('/dashboard', { replace: true });
    } catch (err: any) {
      setError(err.message || 'Failed to authenticate demo account.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#F7F4EE] text-slate-900 dark:bg-[#12100E] dark:text-slate-100 lg:grid lg:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)]">
      {/* ── Context panel ─────────────────────────────────────── */}
      <aside className="relative hidden overflow-hidden bg-teal-900 px-10 py-12 text-paper-50 lg:flex lg:flex-col lg:justify-between xl:px-16">
        <div
          className="pointer-events-none absolute inset-0 opacity-[0.35]"
          style={{
            backgroundImage:
              'radial-gradient(circle at 1px 1px, rgba(247,244,238,0.28) 1px, transparent 0)',
            backgroundSize: '28px 28px',
          }}
        />
        <div className="pointer-events-none absolute -right-32 top-1/4 h-[420px] w-[420px] rounded-full border border-white/10" />
        <div className="pointer-events-none absolute -right-20 top-1/3 h-[260px] w-[260px] rounded-full border border-white/[0.07]" />

        <div className="relative">
          <Link to="/" className="group inline-flex items-center gap-3" aria-label="HireFlow home">
            <BrandMark
              size="md"
              className="ring-white/20 transition-transform duration-300 group-hover:-rotate-3"
            />
            <span className="text-[17px] font-semibold tracking-[-0.035em] text-paper-50">
              HireFlow
            </span>
          </Link>

          <p className="mt-16 max-w-md font-display text-[clamp(2.4rem,3.4vw,3.6rem)] leading-[0.96] tracking-[-0.03em] text-paper-50">
            The hiring inbox,
            <br />
            finally <em className="text-clay-200">accountable</em>.
          </p>
          <p className="mt-6 max-w-sm text-[14px] leading-7 text-paper-50/65">
            Sign in to the workspace where every candidate, attachment, rule decision, and review
            case carries its own history.
          </p>
        </div>

        <div className="relative mt-14 space-y-6">
          {assurances.map(({ icon: Icon, title, body }) => (
            <div key={title} className="flex gap-4">
              <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-white/15 bg-white/[0.06] text-clay-200">
                <Icon className="h-4 w-4" />
              </span>
              <div>
                <p className="text-[13px] font-semibold text-paper-50">{title}</p>
                <p className="mt-1 max-w-xs text-[12px] leading-6 text-paper-50/55">{body}</p>
              </div>
            </div>
          ))}
        </div>

        <div className="relative mt-14 flex items-center justify-between border-t border-white/10 pt-5 text-[11px] text-paper-50/50">
          <span className="inline-flex items-center gap-1.5">
            <ShieldCheck className="h-3.5 w-3.5" />
            Tenant isolated by default
          </span>
          <span className="font-mono">v1.0 · M2/M3</span>
        </div>
      </aside>

      {/* ── Form panel ────────────────────────────────────────── */}
      <main className="relative flex min-h-screen flex-col px-5 py-8 sm:px-8 lg:px-12 xl:px-20">
        <div className="flex items-center justify-between">
          <Link
            to="/"
            className="inline-flex items-center gap-2 text-[12px] font-medium text-slate-500 transition-colors hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            Back to overview
          </Link>
          <Link to="/" className="flex items-center gap-2.5 lg:hidden" aria-label="HireFlow home">
            <BrandMark size="sm" />
            <span className="text-[15px] font-semibold tracking-[-0.03em]">HireFlow</span>
          </Link>
        </div>

        <div className="mx-auto flex w-full max-w-[420px] flex-1 flex-col justify-center py-12">
          <p className="eyebrow">Workspace access</p>
          <h1 className="mt-4 text-[clamp(1.9rem,3vw,2.4rem)] font-semibold leading-[1.08] tracking-[-0.035em] text-slate-950 dark:text-slate-50">
            Sign in to <span className="font-display italic text-teal-700 dark:text-teal-300">HireFlow</span>
          </h1>
          <p className="mt-3 text-[13px] leading-6 text-slate-500 dark:text-slate-400">
            Use your organization account, or open the demo workspace to explore the full pipeline.
          </p>

          <div className="mt-9 rounded-2xl border border-slate-200 bg-white p-6 shadow-lg shadow-slate-900/[0.04] dark:border-slate-800 dark:bg-slate-900 dark:shadow-none sm:p-7">
            {user && isReauth && (
              <div className="mb-5 flex items-start gap-2.5 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2.5 text-[12px] leading-5 text-amber-800 dark:border-amber-800/70 dark:bg-amber-950/40 dark:text-amber-300">
                <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                <span>
                  Currently signed in as <strong className="font-semibold">{user.email}</strong>.
                  Signing in again will replace this session.
                </span>
              </div>
            )}
            {error && (
              <div
                role="alert"
                className="mb-5 flex items-start gap-2.5 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2.5 text-[12px] leading-5 text-rose-700 dark:border-rose-800/70 dark:bg-rose-950/50 dark:text-rose-300"
              >
                <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-rose-500" />
                <span>{error}</span>
              </div>
            )}

            <form onSubmit={handleSubmit} className="space-y-5">
              <div>
                <label
                  htmlFor="login-email"
                  className="mb-1.5 block text-[12px] font-semibold text-slate-700 dark:text-slate-300"
                >
                  Email address
                </label>
                <div className="relative">
                  <Mail className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400 dark:text-slate-500" />
                  <input
                    id="login-email"
                    type="email"
                    autoComplete="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="hr@kartgen.com"
                    className="field pl-9"
                  />
                </div>
              </div>

              <div>
                <label
                  htmlFor="login-password"
                  className="mb-1.5 block text-[12px] font-semibold text-slate-700 dark:text-slate-300"
                >
                  Password
                </label>
                <div className="relative">
                  <Lock className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400 dark:text-slate-500" />
                  <input
                    id="login-password"
                    type={showPassword ? 'text' : 'password'}
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Enter your password"
                    className="field pl-9 pr-10"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((visible) => !visible)}
                    className="absolute right-2.5 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-md text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 dark:text-slate-500 dark:hover:bg-slate-800 dark:hover:text-slate-200"
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
              </div>

              <Button
                type="submit"
                variant="primary"
                className="w-full"
                isLoading={isSubmitting}
                icon={<ArrowRight className="h-4 w-4" />}
              >
                Sign In to Organization
              </Button>
            </form>

            <div className="mt-6 border-t border-slate-200 pt-5 dark:border-slate-800">
              <div className="rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-3 dark:border-slate-800 dark:bg-slate-950/60">
                <div className="flex items-center justify-between">
                  <span className="text-[11px] font-semibold uppercase tracking-[0.1em] text-slate-500 dark:text-slate-400">
                    Quick Demo Credentials
                  </span>
                  <span className="rounded-full border border-teal-200 bg-teal-50 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-teal-700 dark:border-teal-800/70 dark:bg-teal-950/60 dark:text-teal-300">
                    OWNER
                  </span>
                </div>
                <div className="mt-2 space-y-0.5 font-mono text-[11px] text-slate-600 dark:text-slate-400">
                  <p>
                    user <span className="mx-1.5 text-slate-300 dark:text-slate-600">·</span>
                    <strong className="font-medium text-slate-800 dark:text-slate-200">hr@kartgen.com</strong>
                  </p>
                  <p>
                    pass <span className="mx-1.5 text-slate-300 dark:text-slate-600">·</span>
                    <strong className="font-medium text-slate-800 dark:text-slate-200">password123</strong>
                  </p>
                </div>
              </div>

              <Button
                type="button"
                variant="secondary"
                className="mt-3 w-full"
                onClick={handleDemoLogin}
                isLoading={isSubmitting}
                icon={<CheckCircle2 className="h-4 w-4 text-teal-600 dark:text-teal-400" />}
              >
                One-Click Demo Sign In
              </Button>
            </div>
          </div>

          <p className="mt-6 flex items-center justify-center gap-1.5 text-center text-[11px] leading-5 text-slate-500 dark:text-slate-400">
            <ShieldCheck className="h-3.5 w-3.5 shrink-0" />
            Credentials stay server-side. No provider secrets are stored in the browser.
          </p>
        </div>

        <p className="mx-auto w-full max-w-[420px] text-center text-[11px] text-slate-400 dark:text-slate-500">
          © {new Date().getFullYear()} HireFlow. Built for accountable hiring.
        </p>
      </main>
    </div>
  );
};

export default LoginPage;
