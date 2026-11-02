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
  PlusCircle,
  Sliders,
  Check,
  X,
  RefreshCw,
  ExternalLink,
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

  return (
    <div className="space-y-8 animate-in fade-in duration-300">
      {/* Top Welcome & Quick Actions */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-white">Recruitment Operations</h1>
          <p className="text-xs text-slate-400 mt-1">
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

      {/* KPI Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
        {/* Total Candidates */}
        <div className="bg-slate-900 border border-slate-800 p-5 rounded-2xl flex flex-col justify-between hover:border-slate-700 transition-colors">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-400">Total Candidates</span>
            <div className="p-2 rounded-xl bg-emerald-950/60 text-emerald-400 border border-emerald-800/60">
              <Users className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-white tracking-tight">
              {metrics ? metrics.totalCandidates.toLocaleString() : '—'}
            </span>
            <span className="text-[11px] text-slate-500 block mt-0.5">Resolved profiles</span>
          </div>
        </div>

        {/* Total Resume Files */}
        <div className="bg-slate-900 border border-slate-800 p-5 rounded-2xl flex flex-col justify-between hover:border-slate-700 transition-colors">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-400">Resume Files</span>
            <div className="p-2 rounded-xl bg-slate-800 text-slate-300 border border-slate-700">
              <FileText className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-white tracking-tight">
              {metrics ? metrics.totalResumeFiles.toLocaleString() : '—'}
            </span>
            <span className="text-[11px] text-slate-500 block mt-0.5">Ingested attachments</span>
          </div>
        </div>

        {/* Ingestion Queue Depth */}
        <div className="bg-slate-900 border border-slate-800 p-5 rounded-2xl flex flex-col justify-between hover:border-slate-700 transition-colors">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-400">Queue Depth</span>
            <div className="p-2 rounded-xl bg-amber-950/60 text-amber-400 border border-amber-800/60">
              <Clock className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-amber-400 tracking-tight">
              {metrics ? metrics.queueDepth.toLocaleString() : '—'}
            </span>
            <span className="text-[11px] text-slate-500 block mt-0.5">Pending extraction</span>
          </div>
        </div>

        {/* Processed Resumes */}
        <div className="bg-slate-900 border border-slate-800 p-5 rounded-2xl flex flex-col justify-between hover:border-slate-700 transition-colors">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-400">Processed</span>
            <div className="p-2 rounded-xl bg-cyan-950/60 text-cyan-400 border border-cyan-800/60">
              <CheckCircle className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-white tracking-tight">
              {metrics ? metrics.processedCount.toLocaleString() : '—'}
            </span>
            <span className="text-[11px] text-slate-500 block mt-0.5">Successfully parsed</span>
          </div>
        </div>

        {/* Active Inboxes */}
        <div className="bg-slate-900 border border-slate-800 p-5 rounded-2xl flex flex-col justify-between hover:border-slate-700 transition-colors">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-400">Active Inboxes</span>
            <div className="p-2 rounded-xl bg-indigo-950/60 text-indigo-400 border border-indigo-800/60">
              <Inbox className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-white tracking-tight">
              {metrics ? metrics.activeMailConnections.toLocaleString() : '—'}
            </span>
            <span className="text-[11px] text-slate-500 block mt-0.5">OAuth & IMAP</span>
          </div>
        </div>

        {/* AI Token Spend */}
        <div className="bg-slate-900 border border-slate-800 p-5 rounded-2xl flex flex-col justify-between hover:border-slate-700 transition-colors">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-slate-400">AI Token Usage</span>
            <div className="p-2 rounded-xl bg-slate-800 text-slate-300 border border-slate-700">
              <Cpu className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <span className="text-2xl font-bold text-white tracking-tight">
              {metrics ? metrics.aiTokenSpend.toLocaleString() : '0'}
            </span>
            <span className="text-[11px] text-slate-500 block mt-0.5">Tokens consumed</span>
          </div>
        </div>
      </div>

      {/* Review Cases & Quick Workflows Split */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Review Queue (2 Columns) */}
        <div className="lg:col-span-2 bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden flex flex-col">
          <div className="p-5 border-b border-slate-800 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <AlertTriangle className="w-4 h-4 text-amber-400" />
              <h2 className="text-sm font-semibold text-white">Identity & Extraction Review Cases</h2>
            </div>
            <Badge variant={reviewCases.length > 0 ? 'amber' : 'slate'} size="sm">
              {reviewCases.length} Pending
            </Badge>
          </div>

          <div className="divide-y divide-slate-800/60 flex-1 overflow-y-auto max-h-[380px]">
            {reviewCases.length === 0 ? (
              <div className="py-12 px-4 text-center">
                <CheckCircle className="w-8 h-8 text-emerald-400 mx-auto mb-2 opacity-80" />
                <p className="text-xs font-medium text-slate-300">Review Queue Cleared</p>
                <p className="text-[11px] text-slate-500 mt-1">
                  All candidate extractions and rule evaluations matched deterministically.
                </p>
              </div>
            ) : (
              reviewCases.map((rc) => (
                <div key={rc.id} className="p-4 flex items-center justify-between hover:bg-slate-800/30 transition-colors gap-4">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <Badge variant="amber" size="sm">
                        {rc.reasonCode}
                      </Badge>
                      <span className="text-xs font-mono text-slate-500">{rc.id.slice(0, 8)}</span>
                    </div>
                    <p className="text-xs text-slate-300 mt-1.5 line-clamp-1">{rc.details || 'Identity conflict detected during candidate deduplication.'}</p>
                    <span className="text-[10px] text-slate-500 mt-0.5 block">
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

        {/* Quick Launch Cards (1 Column) */}
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 flex flex-col justify-between">
          <div>
            <h2 className="text-sm font-semibold text-white mb-3">Quick Navigation</h2>
            <div className="space-y-2.5">
              <button
                onClick={() => navigate('/candidates')}
                className="w-full p-3 rounded-xl bg-slate-950 border border-slate-800 hover:border-slate-700 flex items-center justify-between text-left transition-colors group"
              >
                <div className="flex items-center gap-3">
                  <Users className="w-4 h-4 text-emerald-400" />
                  <div>
                    <div className="text-xs font-medium text-slate-200 group-hover:text-white">Candidates Database</div>
                    <div className="text-[11px] text-slate-500">Filter and view resume profiles</div>
                  </div>
                </div>
                <ExternalLink className="w-3.5 h-3.5 text-slate-600 group-hover:text-slate-400" />
              </button>

              <button
                onClick={() => navigate('/jobs')}
                className="w-full p-3 rounded-xl bg-slate-950 border border-slate-800 hover:border-slate-700 flex items-center justify-between text-left transition-colors group"
              >
                <div className="flex items-center gap-3">
                  <PlusCircle className="w-4 h-4 text-cyan-400" />
                  <div>
                    <div className="text-xs font-medium text-slate-200 group-hover:text-white">Job Openings</div>
                    <div className="text-[11px] text-slate-500">Post roles and inspect candidate matches</div>
                  </div>
                </div>
                <ExternalLink className="w-3.5 h-3.5 text-slate-600 group-hover:text-slate-400" />
              </button>

              <button
                onClick={() => navigate('/mailboxes')}
                className="w-full p-3 rounded-xl bg-slate-950 border border-slate-800 hover:border-slate-700 flex items-center justify-between text-left transition-colors group"
              >
                <div className="flex items-center gap-3">
                  <Inbox className="w-4 h-4 text-indigo-400" />
                  <div>
                    <div className="text-xs font-medium text-slate-200 group-hover:text-white">Mailbox Sync Hub</div>
                    <div className="text-[11px] text-slate-500">OAuth & Chrome Extension file intake</div>
                  </div>
                </div>
                <ExternalLink className="w-3.5 h-3.5 text-slate-600 group-hover:text-slate-400" />
              </button>

              <button
                onClick={() => navigate('/rules')}
                className="w-full p-3 rounded-xl bg-slate-950 border border-slate-800 hover:border-slate-700 flex items-center justify-between text-left transition-colors group"
              >
                <div className="flex items-center gap-3">
                  <Sliders className="w-4 h-4 text-amber-400" />
                  <div>
                    <div className="text-xs font-medium text-slate-200 group-hover:text-white">Recruitment Rules</div>
                    <div className="text-[11px] text-slate-500">Email filtering & rule preview simulator</div>
                  </div>
                </div>
                <ExternalLink className="w-3.5 h-3.5 text-slate-600 group-hover:text-slate-400" />
              </button>
            </div>
          </div>

          <div className="mt-4 pt-4 border-t border-slate-800">
            <button
              onClick={() => navigate('/docs')}
              className="w-full py-2 px-3 rounded-xl bg-emerald-950/40 border border-emerald-800/40 text-emerald-400 hover:bg-emerald-950/60 text-xs font-medium transition-colors flex items-center justify-center gap-2"
            >
              <span>Explore Technical Architecture Docs</span>
            </button>
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
          <div className="border-2 border-dashed border-slate-700 hover:border-emerald-500/60 rounded-xl p-8 text-center transition-colors bg-slate-950/50">
            <Upload className="w-8 h-8 text-slate-500 mx-auto mb-2" />
            <p className="text-xs text-slate-300 font-medium">Select PDF or DOCX files</p>
            <p className="text-[11px] text-slate-500 mt-1">Content-based MIME verified up to 25 MB</p>
            <input
              type="file"
              multiple
              accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              onChange={(e) => setUploadFiles(e.target.files)}
              className="mt-4 block w-full text-xs text-slate-400 file:mr-4 file:py-2 file:px-4 file:rounded-lg file:border-0 file:text-xs file:font-semibold file:bg-slate-800 file:text-slate-200 hover:file:bg-slate-700 cursor-pointer"
            />
          </div>

          {uploadFiles && uploadFiles.length > 0 && (
            <div className="p-3 rounded-xl bg-slate-950 border border-slate-800 text-xs text-slate-300">
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
            <div className="p-3 bg-slate-950 border border-slate-800 rounded-xl">
              <span className="text-[11px] text-slate-500 block">Conflict Description:</span>
              <p className="text-xs text-slate-300 mt-1">{selectedCase.details || 'Reason code: ' + selectedCase.reasonCode}</p>
            </div>

            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                Resolution Action
              </label>
              <select
                value={resolutionAction}
                onChange={(e) => setResolutionAction(e.target.value)}
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500"
              >
                <option value="MERGE">MERGE (Confirm candidate identity match)</option>
                <option value="ASSIGN_JOB">ASSIGN_JOB (Explicitly map candidate to requisition)</option>
                <option value="CREATE_NEW">CREATE_NEW (Treat as distinct candidate)</option>
                <option value="DISMISS">DISMISS (Ignore conflict)</option>
              </select>
            </div>

            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                Recruiter Notes
              </label>
              <textarea
                value={resolutionNotes}
                onChange={(e) => setResolutionNotes(e.target.value)}
                placeholder="Audit notes for this resolution decision..."
                rows={3}
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500"
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
