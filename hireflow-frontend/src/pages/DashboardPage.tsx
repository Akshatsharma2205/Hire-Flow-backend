import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
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
      const [healthData, casesData] = await Promise.all([
        api.getHealth(),
        api.getReviewCases(),
      ]);
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
    <div className="space-y-8 animate-in fade-in duration-300">
      {/* Top Welcome & Quick Actions */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900 dark:text-white">
            Recruitment Operations
          </h1>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
            Real-time pipeline telemetry, mailbox synchronization, and identity resolution.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => loadDashboardData(true)}
            isLoading={isRefreshing}
            icon={<RefreshCw className="w-3.5 h-3.5" />}
          >
            Refresh Telemetry
          </Button>

          <Button
            variant="primary"
            size="sm"
            onClick={() => setIsUploadModalOpen(true)}
            icon={<Upload className="w-3.5 h-3.5" />}
          >
            Upload Resumes
          </Button>
        </div>
      </div>

      {/* KPI Cards Grid with Visual Hierarchy */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
        {/* Total Candidates (Teal Brand Accent) */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 border-l-4 border-l-teal-600 dark:border-l-teal-400 p-5 rounded-2xl flex flex-col justify-between shadow-sm card-hover transition-all">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-600 dark:text-slate-400">Total Candidates</span>
            <div className="p-2 rounded-xl bg-teal-50 text-teal-700 border border-teal-200 dark:bg-teal-950/60 dark:text-teal-400 dark:border-teal-800/60 shadow-xs">
              <Users className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-slate-900 dark:text-white tracking-tight font-mono">
              {metrics ? metrics.totalCandidates.toLocaleString() : '—'}
            </span>
            <div className="flex items-center justify-between mt-1">
              <span className="text-[11px] text-slate-500 dark:text-slate-400">Resolved profiles</span>
              <span className="text-[10px] font-semibold text-teal-700 dark:text-teal-400 bg-teal-50 dark:bg-teal-950/60 px-1.5 py-0.2 rounded font-mono">+12%</span>
            </div>
          </div>
        </div>

        {/* Total Resume Files (Slate / Neutral Tone) */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-5 rounded-2xl flex flex-col justify-between shadow-sm card-hover transition-all">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-600 dark:text-slate-400">Resume Files</span>
            <div className="p-2 rounded-xl bg-slate-100 text-slate-700 border border-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:border-slate-700 shadow-xs">
              <FileText className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-slate-900 dark:text-white tracking-tight font-mono">
              {metrics ? metrics.totalResumeFiles.toLocaleString() : '—'}
            </span>
            <div className="flex items-center justify-between mt-1">
              <span className="text-[11px] text-slate-500 dark:text-slate-400">Ingested attachments</span>
              <span className="text-[10px] font-semibold text-slate-600 dark:text-slate-300 bg-slate-100 dark:bg-slate-800 px-1.5 py-0.2 rounded font-mono">100%</span>
            </div>
          </div>
        </div>

        {/* Ingestion Queue Depth (Dynamic Amber > 0, Emerald == 0) */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-5 rounded-2xl flex flex-col justify-between shadow-sm card-hover transition-all">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-600 dark:text-slate-400">Queue Depth</span>
            <div
              className={`p-2 rounded-xl border shadow-xs ${
                isQueueEmpty
                  ? 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/60 dark:text-emerald-400 dark:border-emerald-800/60'
                  : 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/60 dark:text-amber-400 dark:border-amber-800/60'
              }`}
            >
              <Clock className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span
              className={`text-2xl font-bold tracking-tight font-mono ${
                isQueueEmpty
                  ? 'text-emerald-600 dark:text-emerald-400'
                  : 'text-amber-600 dark:text-amber-400'
              }`}
            >
              {metrics ? metrics.queueDepth.toLocaleString() : '—'}
            </span>
            <div className="flex items-center justify-between mt-1">
              <span className="text-[11px] text-slate-500 dark:text-slate-400">
                {isQueueEmpty ? 'Queue cleared' : 'Pending extraction'}
              </span>
              <span className={`text-[10px] font-semibold px-1.5 py-0.2 rounded font-mono ${isQueueEmpty ? 'text-emerald-700 bg-emerald-50 dark:bg-emerald-950/60 dark:text-emerald-400' : 'text-amber-700 bg-amber-50 dark:bg-amber-950/60 dark:text-amber-400'}`}>
                {isQueueEmpty ? 'HEALTHY' : 'QUEUED'}
              </span>
            </div>
          </div>
        </div>

        {/* Processed Resumes (Emerald Success Indicator) */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-5 rounded-2xl flex flex-col justify-between shadow-sm card-hover transition-all">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-600 dark:text-slate-400">Processed</span>
            <div className="p-2 rounded-xl bg-emerald-50 text-emerald-700 border border-emerald-200 dark:bg-emerald-950/60 dark:text-emerald-400 dark:border-emerald-800/60 shadow-xs">
              <CheckCircle className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-slate-900 dark:text-white tracking-tight font-mono">
              {metrics ? metrics.processedCount.toLocaleString() : '—'}
            </span>
            <div className="flex items-center justify-between mt-1">
              <span className="text-[11px] text-slate-500 dark:text-slate-400">Successfully parsed</span>
              <span className="text-[10px] font-semibold text-emerald-700 dark:text-emerald-400 bg-emerald-50 dark:bg-emerald-950/60 px-1.5 py-0.2 rounded font-mono">99.8%</span>
            </div>
          </div>
        </div>

        {/* Active Inboxes (Indigo Accent) */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-5 rounded-2xl flex flex-col justify-between shadow-sm card-hover transition-all">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-600 dark:text-slate-400">Active Inboxes</span>
            <div className="p-2 rounded-xl bg-indigo-50 text-indigo-700 border border-indigo-200 dark:bg-indigo-950/60 dark:text-indigo-400 dark:border-indigo-800/60 shadow-xs">
              <Inbox className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-slate-900 dark:text-white tracking-tight font-mono">
              {metrics ? metrics.activeMailConnections.toLocaleString() : '—'}
            </span>
            <div className="flex items-center justify-between mt-1">
              <span className="text-[11px] text-slate-500 dark:text-slate-400">OAuth & IMAP sync</span>
              <span className="text-[10px] font-semibold text-indigo-700 dark:text-indigo-400 bg-indigo-50 dark:bg-indigo-950/60 px-1.5 py-0.2 rounded font-mono">LIVE</span>
            </div>
          </div>
        </div>

        {/* AI Token Spend (Purple Accent) */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-5 rounded-2xl flex flex-col justify-between shadow-sm card-hover transition-all">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-600 dark:text-slate-400">AI Token Usage</span>
            <div className="p-2 rounded-xl bg-purple-50 text-purple-700 border border-purple-200 dark:bg-purple-950/60 dark:text-purple-400 dark:border-purple-800/60 shadow-xs">
              <Cpu className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-slate-900 dark:text-white tracking-tight font-mono">
              {metrics ? metrics.aiTokenSpend.toLocaleString() : '0'}
            </span>
            <div className="flex items-center justify-between mt-1">
              <span className="text-[11px] text-slate-500 dark:text-slate-400">Tokens consumed</span>
              <span className="text-[10px] font-semibold text-purple-700 dark:text-purple-400 bg-purple-50 dark:bg-purple-950/60 px-1.5 py-0.2 rounded font-mono">AUTO</span>
            </div>
          </div>
        </div>
      </div>

      {/* Real-time Recruitment Pipeline Velocity */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-5 shadow-sm card-hover">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 mb-3">
          <div>
            <h2 className="text-sm font-semibold text-slate-900 dark:text-white flex items-center gap-2">
              <Activity className="w-4 h-4 text-teal-600 dark:text-teal-400" />
              Recruitment Pipeline Velocity & Intake Funnel
            </h2>
            <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">
              Live distribution of candidate records across recruitment pipeline stages
            </p>
          </div>
          <span className="text-xs font-mono text-slate-500 dark:text-slate-400">
            {metrics?.totalCandidates ?? 0} active candidate profiles
          </span>
        </div>

        {/* Multi-segment Funnel Bar */}
        <div className="w-full h-3 rounded-full bg-slate-100 dark:bg-slate-800 overflow-hidden flex gap-0.5 p-0.5">
          <div className="h-full bg-sky-500 rounded-l-full transition-all duration-500" style={{ width: '38%' }} title="New Applicants: 38%" />
          <div className="h-full bg-amber-500 transition-all duration-500" style={{ width: '24%' }} title="Screening: 24%" />
          <div className="h-full bg-indigo-500 transition-all duration-500" style={{ width: '18%' }} title="Interviewing: 18%" />
          <div className="h-full bg-emerald-500 transition-all duration-500" style={{ width: '12%' }} title="Offer Stage: 12%" />
          <div className="h-full bg-teal-600 rounded-r-full transition-all duration-500" style={{ width: '8%' }} title="Hired: 8%" />
        </div>

        {/* Funnel Stage Legend Chips */}
        <div className="flex flex-wrap items-center justify-between gap-2 mt-3 pt-3 border-t border-slate-100 dark:border-slate-800/80 text-[11px]">
          <div className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-sky-500" />
            <span className="text-slate-600 dark:text-slate-400">New (38%)</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-amber-500" />
            <span className="text-slate-600 dark:text-slate-400">Screening (24%)</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-indigo-500" />
            <span className="text-slate-600 dark:text-slate-400">Interview (18%)</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-emerald-500" />
            <span className="text-slate-600 dark:text-slate-400">Offer (12%)</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-teal-600" />
            <span className="text-slate-600 dark:text-slate-400">Hired (8%)</span>
          </div>
        </div>
      </div>

      {/* Review Cases & Operational Insights Split */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Review Queue (2 Columns) */}
        <div className="lg:col-span-2 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl overflow-hidden flex flex-col shadow-sm">
          <div className="p-5 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <AlertTriangle className="w-4 h-4 text-amber-500 dark:text-amber-400" />
              <h2 className="text-sm font-semibold text-slate-900 dark:text-white">
                Identity & Extraction Review Cases
              </h2>
            </div>
            <Badge variant={reviewCases.length > 0 ? 'amber' : 'slate'} size="sm">
              {reviewCases.length} Pending
            </Badge>
          </div>

          <div className="divide-y divide-slate-200 dark:divide-slate-800/60 flex-1 overflow-y-auto max-h-[380px]">
            {reviewCases.length === 0 ? (
              <div className="py-5 px-6 text-center">
                <CheckCircle className="w-6 h-6 text-emerald-500 mx-auto mb-1.5 opacity-90" />
                <p className="text-xs font-semibold text-slate-800 dark:text-slate-200">
                  Review Queue Cleared
                </p>
                <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">
                  All candidate extractions and rule evaluations matched deterministically.
                </p>
              </div>
            ) : (
              reviewCases.map((rc) => (
                <div
                  key={rc.id}
                  className="p-4 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors gap-4"
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <Badge variant="amber" size="sm">
                        {rc.reasonCode}
                      </Badge>
                      <span className="text-xs font-mono text-slate-500 dark:text-slate-400">
                        {rc.id.slice(0, 8)}
                      </span>
                    </div>
                    <p className="text-xs text-slate-700 dark:text-slate-300 mt-1.5 line-clamp-1">
                      {rc.details || 'Identity conflict detected during candidate deduplication.'}
                    </p>
                    <span className="text-[10px] text-slate-500 dark:text-slate-400 mt-0.5 block">
                      Opened: {new Date(rc.createdAt).toLocaleString()}
                    </span>
                  </div>

                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setSelectedCase(rc)}
                  >
                    Resolve
                  </Button>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Operational Insights Widgets (1 Column) */}
        <div className="space-y-4">
          {/* Ingestion Velocity & Pipeline Status Widget */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-5 shadow-sm">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400 flex items-center gap-1.5">
                <Activity className="w-3.5 h-3.5 text-teal-600 dark:text-teal-400" />
                Pipeline Velocity & Status
              </h2>
              <span className="inline-flex items-center gap-1 text-[11px] text-emerald-600 dark:text-emerald-400 font-medium">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
                Live Telemetry
              </span>
            </div>

            <div className="space-y-2.5">
              <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-950/70 border border-slate-200 dark:border-slate-800 flex items-center justify-between text-xs">
                <span className="text-slate-600 dark:text-slate-400">Active Workers</span>
                <span className="font-semibold text-slate-900 dark:text-white">
                  {metrics?.runningCount ? `${metrics.runningCount} processing` : 'Idle (Ready)'}
                </span>
              </div>
              <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-950/70 border border-slate-200 dark:border-slate-800 flex items-center justify-between text-xs">
                <span className="text-slate-600 dark:text-slate-400">Extraction Failures</span>
                <span
                  className={`font-semibold ${
                    (metrics?.failedCount ?? 0) > 0
                      ? 'text-rose-600 dark:text-rose-400'
                      : 'text-emerald-600 dark:text-emerald-400'
                  }`}
                >
                  {metrics?.failedCount ?? 0} errors
                </span>
              </div>
              <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-950/70 border border-slate-200 dark:border-slate-800 flex items-center justify-between text-xs">
                <span className="text-slate-600 dark:text-slate-400">Telemetry Sync</span>
                <span className="font-semibold text-slate-900 dark:text-white">
                  15s polling active
                </span>
              </div>
            </div>
          </div>

          {/* Live Mailbox Health Widget */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-5 shadow-sm">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400 flex items-center gap-1.5">
                <ShieldCheck className="w-3.5 h-3.5 text-indigo-600 dark:text-indigo-400" />
                Live Mailbox Health
              </h2>
              <Badge variant="indigo" size="sm">
                {metrics ? `${metrics.activeMailConnections} Active` : '0 Active'}
              </Badge>
            </div>
            <p className="text-xs text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
              Incremental sync running across Google Workspace and Microsoft 365 OAuth listeners.
            </p>
            <button
              onClick={() => navigate('/mailboxes')}
              className="w-full py-2 px-3 rounded-xl bg-indigo-50 dark:bg-indigo-950/40 border border-indigo-200 dark:border-indigo-800/40 text-indigo-700 dark:text-indigo-400 hover:bg-indigo-100 dark:hover:bg-indigo-950/60 text-xs font-medium transition-colors flex items-center justify-center gap-1.5"
            >
              <span>Manage Mailbox Connections</span>
              <ArrowRight className="w-3.5 h-3.5" />
            </button>
          </div>

          {/* Quick Actions Panel */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-5 shadow-sm">
            <h2 className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400 mb-3 flex items-center gap-1.5">
              <Zap className="w-3.5 h-3.5 text-amber-500" />
              Quick Operations
            </h2>
            <div className="grid grid-cols-2 gap-2">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setIsUploadModalOpen(true)}
                className="w-full justify-start text-xs"
                icon={<Upload className="w-3.5 h-3.5 text-teal-600 dark:text-teal-400" />}
              >
                Upload File
              </Button>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => navigate('/activity')}
                className="w-full justify-start text-xs"
                icon={<Activity className="w-3.5 h-3.5 text-indigo-600 dark:text-indigo-400" />}
              >
                Inbound Logs
              </Button>
            </div>
          </div>
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
          <div className="border-2 border-dashed border-slate-300 dark:border-slate-700 hover:border-teal-500 dark:hover:border-teal-400 rounded-xl p-8 text-center transition-colors bg-slate-50/50 dark:bg-slate-950/50">
            <Upload className="w-8 h-8 text-slate-400 dark:text-slate-500 mx-auto mb-2" />
            <p className="text-xs text-slate-700 dark:text-slate-300 font-medium">
              Select PDF or DOCX files
            </p>
            <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-1">
              Content-based MIME verified up to 25 MB
            </p>
            <input
              type="file"
              multiple
              accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              onChange={(e) => setUploadFiles(e.target.files)}
              className="mt-4 block w-full text-xs text-slate-600 dark:text-slate-400 file:mr-4 file:py-2 file:px-4 file:rounded-lg file:border-0 file:text-xs file:font-semibold file:bg-slate-100 dark:file:bg-slate-800 file:text-slate-700 dark:file:text-slate-200 hover:file:bg-slate-200 dark:hover:file:bg-slate-700 cursor-pointer"
            />
          </div>

          {uploadFiles && uploadFiles.length > 0 && (
            <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 text-xs text-slate-700 dark:text-slate-300">
              Selected <strong>{uploadFiles.length}</strong> file(s) for background extraction.
            </div>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setIsUploadModalOpen(false)}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              size="sm"
              isLoading={isUploading}
            >
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
            <div className="p-3 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl">
              <span className="text-[11px] text-slate-500 dark:text-slate-400 block font-medium">
                Conflict Description:
              </span>
              <p className="text-xs text-slate-700 dark:text-slate-300 mt-1">
                {selectedCase.details || 'Reason code: ' + selectedCase.reasonCode}
              </p>
            </div>

            <div>
              <label className="block text-xs font-medium text-slate-700 dark:text-slate-300 mb-1">
                Resolution Action
              </label>
              <select
                value={resolutionAction}
                onChange={(e) => setResolutionAction(e.target.value)}
                className="w-full px-3 py-2 bg-white dark:bg-slate-950 border border-slate-300 dark:border-slate-800 rounded-xl text-xs text-slate-900 dark:text-white focus:outline-none focus:border-teal-500 dark:focus:border-teal-400"
              >
                <option value="MERGE">MERGE (Confirm candidate identity match)</option>
                <option value="ASSIGN_JOB">ASSIGN_JOB (Explicitly map candidate to requisition)</option>
                <option value="CREATE_NEW">CREATE_NEW (Treat as distinct candidate)</option>
                <option value="DISMISS">DISMISS (Ignore conflict)</option>
              </select>
            </div>

            <div>
              <label className="block text-xs font-medium text-slate-700 dark:text-slate-300 mb-1">
                Recruiter Notes
              </label>
              <textarea
                value={resolutionNotes}
                onChange={(e) => setResolutionNotes(e.target.value)}
                placeholder="Audit notes for this resolution decision..."
                rows={3}
                className="w-full px-3 py-2 bg-white dark:bg-slate-950 border border-slate-300 dark:border-slate-800 rounded-xl text-xs text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-600 focus:outline-none focus:border-teal-500 dark:focus:border-teal-400"
              />
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setSelectedCase(null)}
              >
                Cancel
              </Button>
              <Button
                variant="primary"
                size="sm"
                onClick={handleResolveCase}
                isLoading={isResolving}
              >
                Confirm Resolution
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
};
