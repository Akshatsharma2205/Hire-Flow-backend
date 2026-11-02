import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { LucideIcon } from 'lucide-react';
import {
  Users,
  FileText,
  Clock,
  CheckCircle,
  Inbox,
  Cpu,
  AlertTriangle,
  Upload,
  RefreshCw,
  Activity,
  ArrowRight,
  ShieldCheck,
  Zap,
} from 'lucide-react';
import { api } from '../services/api';
import { HealthMetrics, ReviewCase } from '../types';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { useToast } from '../components/common/Toast';

interface MetricCardProps {
  label: string;
  value: string;
  hint: string;
  chip: string;
  icon: LucideIcon;
  highlight?: boolean;
  tone?: 'teal' | 'emerald' | 'amber' | 'indigo' | 'purple' | 'slate';
  emphasis?: 'neutral' | 'success' | 'warning';
}

const toneStyles: Record<NonNullable<MetricCardProps['tone']>, string> = {
  teal: 'border-teal-200 bg-teal-50 text-teal-700 dark:border-teal-800/60 dark:bg-teal-950/60 dark:text-teal-300',
  emerald: 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-800/60 dark:bg-emerald-950/60 dark:text-emerald-300',
  amber: 'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-800/60 dark:bg-amber-950/60 dark:text-amber-300',
  indigo: 'border-indigo-200 bg-indigo-50 text-indigo-700 dark:border-indigo-800/60 dark:bg-indigo-950/60 dark:text-indigo-300',
  purple: 'border-purple-200 bg-purple-50 text-purple-700 dark:border-purple-800/60 dark:bg-purple-950/60 dark:text-purple-300',
  slate: 'border-slate-200 bg-slate-100 text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300',
};

const valueStyles: Record<NonNullable<MetricCardProps['emphasis']>, string> = {
  neutral: 'text-slate-950 dark:text-slate-50',
  success: 'text-emerald-700 dark:text-emerald-400',
  warning: 'text-amber-700 dark:text-amber-400',
};

const MetricCard: React.FC<MetricCardProps> = ({
  label,
  value,
  hint,
  chip,
  icon: Icon,
  highlight = false,
  tone = 'slate',
  emphasis = 'neutral',
}) => (
  <div
    className={`flex flex-col justify-between rounded-xl border p-4 transition-colors ${
      highlight
        ? 'border-teal-800 bg-teal-800 text-paper-50 dark:border-teal-700 dark:bg-teal-900'
        : 'border-slate-200 bg-white hover:border-slate-300 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-slate-700'
    }`}
  >
    <div className="flex items-start justify-between gap-3">
      <span
        className={`text-[11px] font-medium leading-5 ${
          highlight ? 'text-paper-50/70' : 'text-slate-500 dark:text-slate-400'
        }`}
      >
        {label}
      </span>
      <span
        className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border ${
          highlight ? 'border-white/15 bg-white/10 text-paper-50' : toneStyles[tone]
        }`}
      >
        <Icon className="h-3.5 w-3.5" />
      </span>
    </div>

    <div className="mt-4">
      <span
        className={`font-mono text-[24px] font-semibold leading-none tracking-[-0.03em] ${
          highlight ? 'text-paper-50' : valueStyles[emphasis]
        }`}
      >
        {value}
      </span>
      <div className="mt-2.5 flex items-center justify-between gap-2">
        <span className={`truncate text-[10.5px] ${highlight ? 'text-paper-50/60' : 'text-slate-500 dark:text-slate-400'}`}>
          {hint}
        </span>
        <span
          className={`shrink-0 rounded px-1.5 py-0.5 font-mono text-[9px] font-semibold ${
            highlight ? 'bg-white/10 text-paper-50/80' : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400'
          }`}
        >
          {chip}
        </span>
      </div>
    </div>
  </div>
);

const SectionLabel: React.FC<{ icon: LucideIcon; children: React.ReactNode; className?: string }> = ({
  icon: Icon,
  children,
  className = '',
}) => (
  <h2
    className={`flex items-center gap-1.5 text-[10.5px] font-semibold uppercase tracking-[0.14em] text-slate-500 dark:text-slate-400 ${className}`}
  >
    <Icon className="h-3.5 w-3.5" />
    {children}
  </h2>
);

export const DashboardPage: React.FC = () => {
  const navigate = useNavigate();
  const { toast } = useToast();

  const [metrics, setMetrics] = useState<HealthMetrics | null>(null);
  const [reviewCases, setReviewCases] = useState<ReviewCase[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  // Upload modal state
  const [isUploadModalOpen, setIsUploadModalOpen] = useState(false);
  const [uploadFiles, setUploadFiles] = useState<FileList | null>(null);
  const [isUploading, setIsUploading] = useState(false);

  // Review Case resolution modal state
  const [selectedCase, setSelectedCase] = useState<ReviewCase | null>(null);
  const [resolutionAction, setResolutionAction] = useState<string>('MERGE');
  const [resolutionNotes, setResolutionNotes] = useState<string>('');
  const [isResolving, setIsResolving] = useState(false);

  const loadDashboardData = async (silent = false) => {
    if (!silent) setIsLoading(true);
    else setIsRefreshing(true);
    try {
      const [healthData, casesData] = await Promise.all([api.getHealth(), api.getReviewCases()]);
      setMetrics(healthData);
      setReviewCases(casesData || []);
    } catch (err: any) {
      toast(err.message || 'Failed to load dashboard metrics', 'error');
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  };

  useEffect(() => {
    loadDashboardData();
    const interval = setInterval(() => loadDashboardData(true), 15000);
    return () => clearInterval(interval);
  }, []);

  const handleUploadSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!uploadFiles || uploadFiles.length === 0) {
      toast('Please select at least one PDF/DOCX file to upload', 'error');
      return;
    }
    setIsUploading(true);
    try {
      let count = 0;
      for (let i = 0; i < uploadFiles.length; i++) {
        await api.uploadDirectResume(uploadFiles[i], 'UPLOAD');
        count++;
      }
      toast(`Successfully queued ${count} resume file(s) for extraction!`, 'success');
      setIsUploadModalOpen(false);
      setUploadFiles(null);
      loadDashboardData(true);
    } catch (err: any) {
      toast(err.message || 'Direct upload failed', 'error');
    } finally {
      setIsUploading(false);
    }
  };

  const handleResolveCase = async () => {
    if (!selectedCase) return;
    setIsResolving(true);
    try {
      await api.resolveReviewCase(selectedCase.id, {
        action: resolutionAction,
        notes: resolutionNotes || 'Resolved via Executive Dashboard',
      });
      toast(`Review case ${selectedCase.id.slice(0, 8)} successfully resolved`, 'success');
      setSelectedCase(null);
      setResolutionNotes('');
      loadDashboardData(true);
    } catch (err: any) {
      toast(err.message || 'Failed to resolve case', 'error');
    } finally {
      setIsResolving(false);
    }
  };

  const queueDepth = metrics?.queueDepth ?? 0;
  const isQueueEmpty = queueDepth === 0;

  return (
    <div className="animate-in space-y-6 duration-300 fade-in">
      {/* Page header */}
      <div className="flex flex-col justify-between gap-4 md:flex-row md:items-end">
        <div>
          <p className="eyebrow">Operations overview</p>
          <h1 className="mt-2 text-[26px] font-semibold leading-tight tracking-[-0.03em] text-slate-950 dark:text-slate-50">
            Recruitment Operations
          </h1>
          <p className="mt-1.5 max-w-2xl text-[12.5px] leading-6 text-slate-500 dark:text-slate-400">
            Live pipeline telemetry, mailbox synchronisation, and identity resolution across every
            intake channel.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => loadDashboardData(true)}
            isLoading={isRefreshing}
            icon={<RefreshCw className="h-3.5 w-3.5" />}
          >
            Refresh Telemetry
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={() => setIsUploadModalOpen(true)}
            icon={<Upload className="h-3.5 w-3.5" />}
          >
            Upload Resumes
          </Button>
        </div>
      </div>

      {/* KPI grid */}
      <section aria-label="Key recruitment metrics" className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
        <MetricCard
          label="Total Candidates"
          value={metrics ? metrics.totalCandidates.toLocaleString() : '—'}
          hint="Verified profiles"
          chip="+12%"
          icon={Users}
          highlight
        />
        <MetricCard
          label="Resume Files"
          value={metrics ? metrics.totalResumeFiles.toLocaleString() : '—'}
          hint="Attachments"
          chip="100%"
          icon={FileText}
          tone="slate"
        />
        <MetricCard
          label="Queue Depth"
          value={metrics ? metrics.queueDepth.toLocaleString() : '—'}
          hint={isQueueEmpty ? 'Queue cleared' : 'Pending extraction'}
          chip={isQueueEmpty ? 'HEALTHY' : 'QUEUED'}
          icon={Clock}
          tone={isQueueEmpty ? 'emerald' : 'amber'}
          emphasis={isQueueEmpty ? 'success' : 'warning'}
        />
        <MetricCard
          label="Processed"
          value={metrics ? metrics.processedCount.toLocaleString() : '—'}
          hint="Successfully parsed"
          chip="99.8%"
          icon={CheckCircle}
          tone="emerald"
        />
        <MetricCard
          label="Active Inboxes"
          value={metrics ? metrics.activeMailConnections.toLocaleString() : '—'}
          hint="OAuth & IMAP sync"
          chip="LIVE"
          icon={Inbox}
          tone="indigo"
        />
        <MetricCard
          label="AI Token Usage"
          value={metrics ? metrics.aiTokenSpend.toLocaleString() : '0'}
          hint="Tokens consumed"
          chip="AUTO"
          icon={Cpu}
          tone="purple"
        />
      </section>

      {/* Pipeline velocity */}
      <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
        <div className="mb-4 flex flex-col justify-between gap-2 sm:flex-row sm:items-center">
          <div>
            <h2 className="flex items-center gap-2 text-[13px] font-semibold text-slate-900 dark:text-slate-50">
              <Activity className="h-4 w-4 text-teal-700 dark:text-teal-400" />
              Recruitment Pipeline Velocity &amp; Intake Funnel
            </h2>
            <p className="mt-1 text-[11px] text-slate-500 dark:text-slate-400">
              Live distribution of candidate records across recruitment pipeline stages
            </p>
          </div>
          <span className="font-mono text-[11px] text-slate-500 dark:text-slate-400">
            {metrics?.totalCandidates ?? 0} active candidate profiles
          </span>
        </div>

        <div className="flex h-2.5 w-full gap-0.5 overflow-hidden rounded-full bg-slate-100 p-0.5 dark:bg-slate-800">
          <div className="h-full rounded-l-full bg-sky-500 transition-all duration-500" style={{ width: '38%' }} title="New Applicants: 38%" />
          <div className="h-full bg-amber-500 transition-all duration-500" style={{ width: '24%' }} title="Screening: 24%" />
          <div className="h-full bg-indigo-500 transition-all duration-500" style={{ width: '18%' }} title="Interviewing: 18%" />
          <div className="h-full bg-emerald-500 transition-all duration-500" style={{ width: '12%' }} title="Offer Stage: 12%" />
          <div className="h-full rounded-r-full bg-teal-600 transition-all duration-500" style={{ width: '8%' }} title="Hired: 8%" />
        </div>

        <div className="mt-4 flex flex-wrap items-center gap-x-5 gap-y-2 border-t border-slate-100 pt-3 text-[10.5px] dark:border-slate-800">
          {[
            ['bg-sky-500', 'New', '38%'],
            ['bg-amber-500', 'Screening', '24%'],
            ['bg-indigo-500', 'Interview', '18%'],
            ['bg-emerald-500', 'Offer', '12%'],
            ['bg-teal-600', 'Hired', '8%'],
          ].map(([dot, label, pct]) => (
            <span key={label} className="inline-flex items-center gap-1.5 text-slate-600 dark:text-slate-400">
              <span className={`h-1.5 w-1.5 rounded-full ${dot}`} />
              {label}
              <span className="font-mono text-slate-400 dark:text-slate-500">({pct})</span>
            </span>
          ))}
        </div>
      </section>

      {/* Review queue + operational side rail */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="flex flex-col overflow-hidden rounded-xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900 lg:col-span-2">
          <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4 dark:border-slate-800">
            <div className="flex items-center gap-2">
              <AlertTriangle className="h-4 w-4 text-amber-500 dark:text-amber-400" />
              <h2 className="text-[13px] font-semibold text-slate-900 dark:text-slate-50">
                Identity &amp; Extraction Review Cases
              </h2>
            </div>
            <Badge variant={reviewCases.length > 0 ? 'amber' : 'slate'} size="sm">
              {reviewCases.length} Pending
            </Badge>
          </div>

          <div className="max-h-[380px] flex-1 divide-y divide-slate-100 overflow-y-auto dark:divide-slate-800/60">
            {reviewCases.length === 0 ? (
              <div className="px-6 py-10 text-center">
                <span className="mx-auto mb-2.5 flex h-9 w-9 items-center justify-center rounded-full bg-emerald-50 dark:bg-emerald-950/50">
                  <CheckCircle className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                </span>
                <p className="text-[12px] font-semibold text-slate-800 dark:text-slate-200">
                  Review Queue Cleared
                </p>
                <p className="mt-1 text-[11px] text-slate-500 dark:text-slate-400">
                  All candidate extractions and rule evaluations matched deterministically.
                </p>
              </div>
            ) : (
              reviewCases.map((rc) => (
                <div
                  key={rc.id}
                  className="flex items-center justify-between gap-4 px-5 py-4 transition-colors hover:bg-slate-50/80 dark:hover:bg-slate-800/30"
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge variant="amber" size="sm">
                        {rc.reasonCode}
                      </Badge>
                      <span className="font-mono text-[10.5px] text-slate-400 dark:text-slate-500">
                        {rc.id.slice(0, 8)}
                      </span>
                    </div>
                    <p className="mt-2 line-clamp-1 text-[12px] text-slate-700 dark:text-slate-300">
                      {rc.details || 'Identity conflict detected during candidate deduplication.'}
                    </p>
                    <span className="mt-1 block text-[10px] text-slate-400 dark:text-slate-500">
                      Opened: {new Date(rc.createdAt).toLocaleString()}
                    </span>
                  </div>
                  <Button variant="outline" size="sm" onClick={() => setSelectedCase(rc)}>
                    Resolve
                  </Button>
                </div>
              ))
            )}
          </div>
        </section>

        <div className="space-y-4">
          <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-3 flex items-center justify-between gap-2">
              <SectionLabel icon={Activity}>Pipeline Velocity &amp; Status</SectionLabel>
              <span className="inline-flex shrink-0 items-center gap-1 text-[10px] font-medium text-emerald-600 dark:text-emerald-400">
                <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-emerald-500" />
                Live
              </span>
            </div>

            <dl className="space-y-2">
              <div className="flex items-center justify-between rounded-lg border border-slate-100 bg-slate-50/70 px-3 py-2 text-[11px] dark:border-slate-800 dark:bg-slate-950/50">
                <dt className="text-slate-500 dark:text-slate-400">Active Workers</dt>
                <dd className="font-semibold text-slate-900 dark:text-slate-100">
                  {metrics?.runningCount ? `${metrics.runningCount} processing` : 'Idle (Ready)'}
                </dd>
              </div>
              <div className="flex items-center justify-between rounded-lg border border-slate-100 bg-slate-50/70 px-3 py-2 text-[11px] dark:border-slate-800 dark:bg-slate-950/50">
                <dt className="text-slate-500 dark:text-slate-400">Extraction Failures</dt>
                <dd
                  className={`font-semibold ${
                    (metrics?.failedCount ?? 0) > 0
                      ? 'text-rose-600 dark:text-rose-400'
                      : 'text-emerald-600 dark:text-emerald-400'
                  }`}
                >
                  {metrics?.failedCount ?? 0} errors
                </dd>
              </div>
              <div className="flex items-center justify-between rounded-lg border border-slate-100 bg-slate-50/70 px-3 py-2 text-[11px] dark:border-slate-800 dark:bg-slate-950/50">
                <dt className="text-slate-500 dark:text-slate-400">Telemetry Sync</dt>
                <dd className="font-semibold text-slate-900 dark:text-slate-100">15s polling</dd>
              </div>
            </dl>
          </section>

          <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-3 flex items-center justify-between gap-2">
              <SectionLabel icon={ShieldCheck}>Live Mailbox Health</SectionLabel>
              <Badge variant="indigo" size="sm">
                {metrics ? `${metrics.activeMailConnections} Active` : '0 Active'}
              </Badge>
            </div>
            <p className="mb-3 text-[11px] leading-5 text-slate-500 dark:text-slate-400">
              Incremental sync across Google Workspace and Microsoft 365 OAuth listeners.
            </p>
            <button
              onClick={() => navigate('/mailboxes')}
              className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] font-semibold text-slate-700 transition-colors hover:border-slate-300 hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-200 dark:hover:bg-slate-800"
            >
              Manage Mailbox Connections
              <ArrowRight className="h-3.5 w-3.5" />
            </button>
          </section>

          <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
            <SectionLabel icon={Zap} className="mb-3">
              Quick Operations
            </SectionLabel>
            <div className="grid grid-cols-2 gap-2">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setIsUploadModalOpen(true)}
                className="w-full justify-start text-xs"
                icon={<Upload className="h-3.5 w-3.5 text-teal-600 dark:text-teal-400" />}
              >
                Upload File
              </Button>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => navigate('/activity')}
                className="w-full justify-start text-xs"
                icon={<Activity className="h-3.5 w-3.5 text-indigo-600 dark:text-indigo-400" />}
              >
                Inbound Logs
              </Button>
            </div>
          </section>
        </div>
      </div>

      {/* Direct Upload Modal */}
      <Modal
        isOpen={isUploadModalOpen}
        onClose={() => setIsUploadModalOpen(false)}
        title="Direct Resume File Intake"
        subtitle="Upload candidate CVs in PDF or DOCX format for extraction"
      >
        <form onSubmit={handleUploadSubmit} className="space-y-4">
          <div className="rounded-xl border-2 border-dashed border-slate-300 bg-slate-50/50 p-8 text-center transition-colors hover:border-teal-500 dark:border-slate-700 dark:bg-slate-950/50 dark:hover:border-teal-400">
            <Upload className="mx-auto mb-2 h-7 w-7 text-slate-400 dark:text-slate-500" />
            <p className="text-[12px] font-medium text-slate-700 dark:text-slate-300">
              Select PDF or DOCX files
            </p>
            <p className="mt-1 text-[11px] text-slate-500 dark:text-slate-400">
              Content-based MIME verified up to 25 MB
            </p>
            <input
              type="file"
              multiple
              accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              onChange={(e) => setUploadFiles(e.target.files)}
              className="mt-4 block w-full cursor-pointer text-xs text-slate-600 file:mr-4 file:rounded-lg file:border-0 file:bg-slate-100 file:px-4 file:py-2 file:text-xs file:font-semibold file:text-slate-700 hover:file:bg-slate-200 dark:text-slate-400 dark:file:bg-slate-800 dark:file:text-slate-200 dark:hover:file:bg-slate-700"
            />
          </div>

          {uploadFiles && uploadFiles.length > 0 && (
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-[11px] text-slate-700 dark:border-slate-800 dark:bg-slate-950 dark:text-slate-300">
              Selected <strong>{uploadFiles.length}</strong> file(s) for background extraction.
            </div>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="outline" size="sm" onClick={() => setIsUploadModalOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="primary" size="sm" isLoading={isUploading}>
              Queue for Extraction
            </Button>
          </div>
        </form>
      </Modal>

      {/* Resolve Case Modal */}
      {selectedCase && (
        <Modal
          isOpen={true}
          onClose={() => setSelectedCase(null)}
          title="Resolve Identity Review Case"
          subtitle={`Case ID: ${selectedCase.id}`}
        >
          <div className="space-y-4">
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 dark:border-slate-800 dark:bg-slate-950">
              <span className="block text-[11px] font-medium text-slate-500 dark:text-slate-400">
                Conflict Description:
              </span>
              <p className="mt-1 text-[12px] text-slate-700 dark:text-slate-300">
                {selectedCase.details || 'Reason code: ' + selectedCase.reasonCode}
              </p>
            </div>

            <div>
              <label className="mb-1 block text-[11px] font-medium text-slate-700 dark:text-slate-300">
                Resolution Action
              </label>
              <select
                value={resolutionAction}
                onChange={(e) => setResolutionAction(e.target.value)}
                className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-xs text-slate-900 focus:border-teal-500 focus:outline-none dark:border-slate-800 dark:bg-slate-950 dark:text-white dark:focus:border-teal-400"
              >
                <option value="MERGE">MERGE (Confirm candidate identity match)</option>
                <option value="ASSIGN_JOB">ASSIGN_JOB (Explicitly map candidate to requisition)</option>
                <option value="CREATE_NEW">CREATE_NEW (Treat as distinct candidate)</option>
                <option value="DISMISS">DISMISS (Ignore conflict)</option>
              </select>
            </div>

            <div>
              <label className="mb-1 block text-[11px] font-medium text-slate-700 dark:text-slate-300">
                Recruiter Notes
              </label>
              <textarea
                value={resolutionNotes}
                onChange={(e) => setResolutionNotes(e.target.value)}
                placeholder="Audit notes for this resolution decision..."
                rows={3}
                className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-xs text-slate-900 placeholder-slate-400 focus:border-teal-500 focus:outline-none dark:border-slate-800 dark:bg-slate-950 dark:text-white dark:placeholder-slate-600 dark:focus:border-teal-400"
              />
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <Button variant="outline" size="sm" onClick={() => setSelectedCase(null)}>
                Cancel
              </Button>
              <Button variant="primary" size="sm" onClick={handleResolveCase} isLoading={isResolving}>
                Confirm Resolution
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
};
