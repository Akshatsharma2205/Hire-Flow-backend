import React, { useEffect, useState } from 'react';
import {
  Kanban,
  List,
  Plus,
  Clock,
  ChevronRight,
  ArrowRight,
  History,
  User,
  Briefcase,
  FileCheck,
} from 'lucide-react';
import { api } from '../services/api';
import { ApplicationDto, ApplicationHistoryDto, Candidate, JobDto } from '../types';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { useToast } from '../components/common/Toast';

const PIPELINE_STAGES = [
  { id: 'APPLIED', label: 'Applied', color: 'slate' as const },
  { id: 'SCREENING', label: 'Screening', color: 'indigo' as const },
  { id: 'INTERVIEW', label: 'Interview', color: 'cyan' as const },
  { id: 'OFFER', label: 'Offer', color: 'amber' as const },
  { id: 'HIRED', label: 'Hired', color: 'emerald' as const },
  { id: 'REJECTED', label: 'Rejected', color: 'rose' as const },
];

export const ApplicationsPage: React.FC = () => {
  const { toast } = useToast();

  const [applications, setApplications] = useState<ApplicationDto[]>([]);
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [jobs, setJobs] = useState<JobDto[]>([]);
  const [viewMode, setViewMode] = useState<'kanban' | 'table'>('kanban');
  const [isLoading, setIsLoading] = useState(true);

  // Transition stage modal
  const [transitionApp, setTransitionApp] = useState<ApplicationDto | null>(null);
  const [targetStage, setTargetStage] = useState('SCREENING');
  const [stageNote, setStageNote] = useState('');
  const [isSubmittingStage, setIsSubmittingStage] = useState(false);

  // Audit history modal
  const [historyApp, setHistoryApp] = useState<ApplicationDto | null>(null);
  const [historyList, setHistoryList] = useState<ApplicationHistoryDto[]>([]);
  const [isHistoryLoading, setIsHistoryLoading] = useState(false);

  // Create Application modal
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [selectedCandidateId, setSelectedCandidateId] = useState('');
  const [selectedJobId, setSelectedJobId] = useState('');
  const [isCreatingApp, setIsCreatingApp] = useState(false);

  const loadData = async () => {
    setIsLoading(true);
    try {
      const [apps, candRes, jobRes] = await Promise.all([
        api.getApplications(),
        api.getCandidates(0, 100).catch(() => ({ content: [] })),
        api.getJobs().catch(() => []),
      ]);
      setApplications(apps || []);
      setCandidates(candRes.content || []);
      setJobs(jobRes || []);
    } catch (err: any) {
      toast('Failed to load application pipeline', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleStageTransition = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!transitionApp) return;
    setIsSubmittingStage(true);
    try {
      await api.updateApplicationStage(transitionApp.id, targetStage, stageNote);
      toast(`Updated stage to ${targetStage}`, 'success');
      setTransitionApp(null);
      setStageNote('');
      loadData();
    } catch (err: any) {
      toast(err.message || 'Stage transition failed', 'error');
    } finally {
      setIsSubmittingStage(false);
    }
  };

  const handleOpenHistory = async (app: ApplicationDto) => {
    setHistoryApp(app);
    setIsHistoryLoading(true);
    try {
      const logs = await api.getApplicationHistory(app.id);
      setHistoryList(logs || []);
    } catch (err) {
      toast('Failed to load stage audit history', 'error');
    } finally {
      setIsHistoryLoading(false);
    }
  };

  const handleCreateApplication = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedCandidateId || !selectedJobId) {
      toast('Candidate and Job are required', 'error');
      return;
    }
    setIsCreatingApp(true);
    try {
      await api.createApplication({
        candidateId: selectedCandidateId,
        jobId: selectedJobId,
      });
      toast('Application linked successfully!', 'success');
      setIsCreateModalOpen(false);
      setSelectedCandidateId('');
      setSelectedJobId('');
      loadData();
    } catch (err: any) {
      toast(err.message || 'Failed to create application', 'error');
    } finally {
      setIsCreatingApp(false);
    }
  };

  const normalizeStage = (stageStr?: string) => {
    const upper = (stageStr || '').toUpperCase();
    if (upper === 'NEW') return 'APPLIED';
    return upper || 'APPLIED';
  };

  return (
    <div className="space-y-6 animate-in fade-in duration-300">
      {/* Top Bar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-white">Application Pipeline</h1>
          <p className="text-xs text-slate-400 mt-1">
            Track candidates across recruitment stages with verifiable audit histories.
          </p>
        </div>

        <div className="flex items-center gap-3">
          {/* View mode toggle */}
          <div className="bg-slate-900 border border-slate-800 p-1 rounded-xl flex items-center">
            <button
              onClick={() => setViewMode('kanban')}
              className={`p-1.5 rounded-lg text-xs transition-colors ${
                viewMode === 'kanban'
                  ? 'bg-slate-800 text-white shadow-sm'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
              title="Kanban Board"
            >
              <Kanban className="w-4 h-4" />
            </button>
            <button
              onClick={() => setViewMode('table')}
              className={`p-1.5 rounded-lg text-xs transition-colors ${
                viewMode === 'table'
                  ? 'bg-slate-800 text-white shadow-sm'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
              title="Table View"
            >
              <List className="w-4 h-4" />
            </button>
          </div>

          <Button
            variant="primary"
            size="sm"
            onClick={() => setIsCreateModalOpen(true)}
            icon={<Plus className="w-4 h-4" />}
          >
            New Application
          </Button>
        </div>
      </div>

      {/* Main Content Area */}
      {isLoading ? (
        <div className="py-24 text-center text-slate-500 text-xs">
          Loading applications pipeline...
        </div>
      ) : viewMode === 'kanban' ? (
        /* Kanban Board View */
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4 overflow-x-auto pb-6">
          {PIPELINE_STAGES.map((col) => {
            const colApps = applications.filter(
              (a) => normalizeStage(a.currentStage) === col.id
            );

            return (
              <div
                key={col.id}
                className="bg-slate-900/80 border border-slate-800 rounded-2xl p-3 flex flex-col min-w-[200px]"
              >
                {/* Column Header */}
                <div className="flex items-center justify-between pb-3 mb-3 border-b border-slate-800">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold text-white tracking-tight">
                      {col.label}
                    </span>
                  </div>
                  <Badge variant={col.color} size="sm">
                    {colApps.length}
                  </Badge>
                </div>

                {/* Cards Container */}
                <div className="space-y-2.5 flex-1 min-h-[350px]">
                  {colApps.length === 0 ? (
                    <div className="h-28 border border-dashed border-slate-800/80 rounded-xl flex items-center justify-center text-[11px] text-slate-600">
                      No candidates
                    </div>
                  ) : (
                    colApps.map((app) => (
                      <div
                        key={app.id}
                        className="bg-slate-950 border border-slate-800 hover:border-slate-700 p-3 rounded-xl transition-all shadow-md group"
                      >
                        <div className="flex items-start justify-between gap-1">
                          <span className="font-semibold text-white text-xs group-hover:text-emerald-400 transition-colors line-clamp-1">
                            {app.candidateName || 'Applicant'}
                          </span>
                          <button
                            onClick={() => handleOpenHistory(app)}
                            className="text-slate-500 hover:text-slate-300 p-1 rounded"
                            title="Audit history"
                          >
                            <History className="w-3.5 h-3.5" />
                          </button>
                        </div>

                        <span className="text-[11px] text-slate-400 flex items-center gap-1 mt-1 truncate">
                          <Briefcase className="w-3 h-3 text-slate-500 shrink-0" />
                          {app.jobTitle || 'General Opening'}
                        </span>

                        <div className="mt-3 pt-2 border-t border-slate-900 flex items-center justify-between">
                          <span className="text-[10px] text-slate-500">
                            {new Date(app.createdAt).toLocaleDateString()}
                          </span>
                          <button
                            onClick={() => {
                              setTransitionApp(app);
                              setTargetStage(col.id === 'APPLIED' ? 'SCREENING' : 'INTERVIEW');
                            }}
                            className="text-[11px] text-emerald-400 hover:underline flex items-center gap-0.5"
                          >
                            <span>Move</span>
                            <ChevronRight className="w-3 h-3" />
                          </button>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        /* Tabular List View */
        <div className="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden shadow-xl">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="bg-slate-950/70 border-b border-slate-800 text-slate-400 font-semibold uppercase tracking-wider text-[11px]">
                <th className="py-3.5 px-5">Candidate</th>
                <th className="py-3.5 px-4">Job Requisition</th>
                <th className="py-3.5 px-4">Stage</th>
                <th className="py-3.5 px-4">Application Date</th>
                <th className="py-3.5 px-5 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/60">
              {applications.length === 0 ? (
                <tr>
                  <td colSpan={5} className="py-16 text-center text-slate-500">
                    No applications currently registered.
                  </td>
                </tr>
              ) : (
                applications.map((app) => (
                  <tr key={app.id} className="hover:bg-slate-800/40 transition-colors">
                    <td className="py-3.5 px-5 font-semibold text-white">
                      {app.candidateName || 'Applicant'}
                    </td>
                    <td className="py-3.5 px-4 text-slate-300">
                      {app.jobTitle || 'General Opening'}
                    </td>
                    <td className="py-3.5 px-4">
                      <Badge variant="indigo" size="sm">
                        {normalizeStage(app.currentStage)}
                      </Badge>
                    </td>
                    <td className="py-3.5 px-4 text-slate-400 font-mono text-[11px]">
                      {new Date(app.createdAt).toLocaleDateString()}
                    </td>
                    <td className="py-3.5 px-5 text-right space-x-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => handleOpenHistory(app)}
                      >
                        History
                      </Button>
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => {
                          setTransitionApp(app);
                          setTargetStage('SCREENING');
                        }}
                      >
                        Transition
                      </Button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* Stage Transition Modal */}
      {transitionApp && (
        <Modal
          isOpen={true}
          onClose={() => setTransitionApp(null)}
          title={`Progress Candidate Stage`}
          subtitle={`Candidate: ${transitionApp.candidateName || 'Applicant'}`}
        >
          <form onSubmit={handleStageTransition} className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                Target Pipeline Stage
              </label>
              <select
                value={targetStage}
                onChange={(e) => setTargetStage(e.target.value)}
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500"
              >
                {PIPELINE_STAGES.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.label}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                Recruiter Audit Note
              </label>
              <textarea
                value={stageNote}
                onChange={(e) => setStageNote(e.target.value)}
                placeholder="Rationale for stage progression or interview score..."
                rows={3}
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500"
              />
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setTransitionApp(null)}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                variant="primary"
                size="sm"
                isLoading={isSubmittingStage}
              >
                Confirm Transition
              </Button>
            </div>
          </form>
        </Modal>
      )}

      {/* Stage Audit History Modal */}
      {historyApp && (
        <Modal
          isOpen={true}
          onClose={() => setHistoryApp(null)}
          title={`Stage Audit Trail: ${historyApp.candidateName || 'Application'}`}
          subtitle={`Application ID: ${historyApp.id}`}
        >
          <div className="space-y-4">
            {isHistoryLoading ? (
              <div className="py-12 text-center text-slate-500 text-xs">
                Retrieving immutable stage audit logs...
              </div>
            ) : historyList.length === 0 ? (
              <div className="py-8 text-center text-slate-500 text-xs">
                No intermediate stage transitions recorded for this application.
              </div>
            ) : (
              <div className="relative pl-6 space-y-4 before:absolute before:left-2 before:top-2 before:bottom-2 before:w-0.5 before:bg-slate-800">
                {historyList.map((h, i) => (
                  <div key={h.id || i} className="relative">
                    <span className="absolute -left-6 top-1 w-2.5 h-2.5 rounded-full bg-emerald-500 ring-4 ring-slate-900" />
                    <div className="p-3 bg-slate-950 border border-slate-800 rounded-xl text-xs">
                      <div className="flex items-center justify-between">
                        <span className="font-semibold text-white">
                          {h.fromStage} → <span className="text-emerald-400">{h.toStage}</span>
                        </span>
                        <span className="text-[10px] text-slate-500 font-mono">
                          {new Date(h.createdAt).toLocaleString()}
                        </span>
                      </div>
                      {h.note && (
                        <p className="text-slate-400 text-[11px] mt-1.5 bg-slate-900/50 p-2 rounded">
                          "{h.note}"
                        </p>
                      )}
                      <span className="text-[10px] text-slate-600 block mt-1">
                        Recorded by: {h.changedBy || 'System / HR Recruiter'}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </Modal>
      )}

      {/* Create Application Modal */}
      <Modal
        isOpen={isCreateModalOpen}
        onClose={() => setIsCreateModalOpen(false)}
        title="Link Candidate to Requisition"
        subtitle="Initiate recruitment pipeline for an existing candidate"
      >
        <form onSubmit={handleCreateApplication} className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-slate-300 mb-1">
              Select Candidate *
            </label>
            <select
              value={selectedCandidateId}
              onChange={(e) => setSelectedCandidateId(e.target.value)}
              className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500"
              required
            >
              <option value="">-- Choose Candidate --</option>
              {candidates.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.displayName || 'Candidate'} ({c.primaryEmail})
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-300 mb-1">
              Select Target Job *
            </label>
            <select
              value={selectedJobId}
              onChange={(e) => setSelectedJobId(e.target.value)}
              className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500"
              required
            >
              <option value="">-- Choose Job Opening --</option>
              {jobs.map((j) => (
                <option key={j.id} value={j.id}>
                  {j.title} ({j.department || 'Engineering'})
                </option>
              ))}
            </select>
          </div>

          <div className="flex justify-end gap-2 pt-3">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setIsCreateModalOpen(false)}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              size="sm"
              isLoading={isCreatingApp}
            >
              Create Application
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
};
