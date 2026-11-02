import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Sparkles, Lock, Mail, Eye, EyeOff, ArrowRight, ShieldCheck, CheckCircle2 } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { Button } from '../components/common/Button';

export const LoginPage: React.FC = () => {
  const { login, quickDemoLogin, user } = useAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (user) {
      navigate('/dashboard', { replace: true });
    }
  }, [user, navigate]);

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
    <div className="min-h-screen bg-[#F7F8FA] dark:bg-[#0B1220] text-slate-900 dark:text-slate-100 flex flex-col justify-center py-12 sm:px-6 lg:px-8 relative overflow-hidden transition-colors">
      {/* Background ambient lighting */}
      <div className="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[350px] bg-teal-600/10 dark:bg-teal-400/10 rounded-full blur-[120px] pointer-events-none" />

      <div className="sm:mx-auto sm:w-full sm:max-w-md z-10">
        <div className="flex justify-center">
          <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-teal-500 to-teal-800 flex items-center justify-center text-white shadow-xl shadow-teal-900/20">
            <Sparkles className="w-7 h-7 text-white" />
          </div>
        </div>
        <h2 className="mt-5 text-center text-2xl font-bold tracking-tight text-slate-900 dark:text-white">
          Sign in to <span className="text-teal-600 dark:text-teal-400">HireFlow AI</span>
        </h2>
        <p className="mt-1.5 text-center text-xs text-slate-500 dark:text-slate-400">
          Intelligent recruitment mailbox ingestion & candidate platform
        </p>
      </div>

      <div className="mt-8 sm:mx-auto sm:w-full sm:max-w-md z-10 px-4">
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 py-8 px-6 shadow-xl shadow-slate-200/50 dark:shadow-none rounded-2xl sm:px-10">
          {error && (
            <div className="mb-5 p-3 rounded-xl bg-rose-50 dark:bg-rose-950/60 border border-rose-200 dark:border-rose-800 text-rose-700 dark:text-rose-300 text-xs flex items-center gap-2">
              <span className="w-1.5 h-1.5 rounded-full bg-rose-500 dark:bg-rose-400" />
              <span>{error}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-slate-700 dark:text-slate-300 mb-1">
                Email address
              </label>
              <div className="relative">
                <Mail className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 dark:text-slate-500" />
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="hr@kartgen.com"
                  className="w-full pl-9 pr-4 py-2 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl text-xs text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-600 focus:outline-none focus:border-teal-600 dark:focus:border-teal-400 focus:ring-1 focus:ring-teal-500/20"
                />
              </div>
            </div>

            <div>
              <label className="block text-xs font-medium text-slate-700 dark:text-slate-300 mb-1">
                Password
              </label>
              <div className="relative">
                <Lock className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 dark:text-slate-500" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••••••"
                  className="w-full pl-9 pr-10 py-2 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl text-xs text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-600 focus:outline-none focus:border-teal-600 dark:focus:border-teal-400 focus:ring-1 focus:ring-teal-500/20"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300"
                >
                  {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
            </div>

            <Button
              type="submit"
              variant="primary"
              className="w-full mt-2"
              isLoading={isSubmitting}
              icon={<ArrowRight className="w-4 h-4" />}
            >
              Sign In to Organization
            </Button>
          </form>

          {/* Quick Demo Credentials Button */}
          <div className="mt-6 pt-5 border-t border-slate-200 dark:border-slate-800">
            <div className="p-3 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl mb-3">
              <div className="flex items-center justify-between text-[11px] text-slate-500 dark:text-slate-400 mb-1">
                <span className="font-semibold text-slate-700 dark:text-slate-300">Quick Demo Credentials</span>
                <span className="text-teal-600 dark:text-teal-400 font-mono text-[10px]">OWNER role</span>
              </div>
              <div className="text-[11px] font-mono text-slate-600 dark:text-slate-400 flex flex-col gap-0.5">
                <span>User: <strong className="text-slate-800 dark:text-slate-200">hr@kartgen.com</strong></span>
                <span>Pass: <strong className="text-slate-800 dark:text-slate-200">password123</strong></span>
              </div>
            </div>

            <Button
              type="button"
              variant="secondary"
              className="w-full text-xs"
              onClick={handleDemoLogin}
              isLoading={isSubmitting}
              icon={<CheckCircle2 className="w-3.5 h-3.5 text-teal-600 dark:text-teal-400" />}
            >
              One-Click Demo Sign In
            </Button>
          </div>

          <div className="mt-5 text-center flex items-center justify-center gap-1.5 text-[11px] text-slate-500 dark:text-slate-400">
            <ShieldCheck className="w-3.5 h-3.5 text-slate-400 dark:text-slate-500" />
            <span>Zero OAuth secrets stored client-side. Multi-tenant isolated.</span>
          </div>
        </div>
      </div>
    </div>
  );
};
