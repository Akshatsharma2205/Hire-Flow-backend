import React, { useEffect, useState } from 'react';
import {
  Briefcase,
  Plus,
  MapPin,
  Building,
  Users,
  Clock,
  Sparkles,
  Layers,
  ChevronRight,
  CheckCircle2,
  AlertCircle,
  FileText,
} from 'lucide-react';
import { api } from '../services/api';
import { JobDto, JobMatchDto } from '../types';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { Drawer } from '../components/common/Drawer';
import { useToast } from '../components/common/Toast';

export const JobsPage: React.FC = () => {
  const { toast } = useToast();

  const [jobs, setJobs] = useState<JobDto[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  // Create Job Modal state
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newDepartment, setNewDepartment] = useState('');
  const [newLocation, setNewLocation] = useState('');
  const [newMinExp, setNewMinExp] = useState(24);
  const [newMaxExp, setNewMaxExp] = useState(96);
  const [newDescription, setNewDescription] = useState('');
  const [isSubmittingJob, setIsSubmittingJob] = useState(false);

  // New Version Revision Modal state
  const [revisionJob, setRevisionJob] = useState<JobDto | null>(null);
  const [revisionDesc, setRevisionDesc] = useState('');
  const [isSubmittingRevision, setIsSubmittingRevision] = useState(false);

  // Candidate Matches Drawer state
  const [matchJob, setMatchJob] = useState<JobDto | null>(null);
  const [matches, setMatches] = useState<JobMatchDto[]>([]);
  const [isMatchLoading, setIsMatchLoading] = useState(false);

  const loadJobs = async () => {
    setIsLoading(true);
    try {
      const data = await api.getJobs();
      setJobs(data || []);
    } catch (err: any) {
      toast(err.message || 'Failed to load job openings', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadJobs();
  }, []);

  const handleCreateJob = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim() || !newDescription.trim()) {
      toast('Title and Job Description are required', 'error');
      return;
    }
    setIsSubmittingJob(true);
    try {
      await api.createJob({
        title: newTitle.trim(),
        department: newDepartment.trim() || 'Engineering',
        location: newLocation.trim() || 'Remote / Hybrid',
        minExperienceMonths: Number(newMinExp),
        maxExperienceMonths: Number(newMaxExp),
        description: newDescription.trim(),
      });
      toast(`Successfully created requisition: ${newTitle}`, 'success');
      setIsCreateModalOpen(false);
      setNewTitle('');
      setNewDepartment('');
      setNewLocation('');
      setNewDescription('');
      loadJobs();
    } catch (err: any) {
      toast(err.message || 'Failed to create job', 'error');
    } finally {
      setIsSubmittingJob(false);
    }
  };

  const handleCreateRevision = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!revisionJob || !revisionDesc.trim()) return;
    setIsSubmittingRevision(true);
    try {
      await api.createJobVersion(revisionJob.id, revisionDesc.trim());
      toast(`Published new revision for ${revisionJob.title}`, 'success');
      setRevisionJob(null);
      setRevisionDesc('');
      loadJobs();
    } catch (err: any) {
      toast(err.message || 'Failed to update job revision', 'error');
    } finally {
      setIsSubmittingRevision(false);
    }
  };

  const handleOpenMatches = async (job: JobDto) => {
    setMatchJob(job);
    setIsMatchLoading(true);
    try {
      const data = await api.getJobMatches(job.id);
      setMatches(data || []);
    } catch (err: any) {
      toast('Failed to load candidate match rankings', 'error');
    } finally {
      setIsMatchLoading(false);
    }
  };

  const handleToggleStatus = async (job: JobDto) => {
    const nextStatus = job.status === 'ACTIVE' ? 'CLOSED' : 'ACTIVE';
    try {
      await api.updateJobStatus(job.id, nextStatus);
      toast(`Requisition ${job.title} is now ${nextStatus}`, 'success');
      loadJobs();
    } catch (err: any) {
      toast('Failed to update job status', 'error');
    }
  };

  return (
    <div className="space-y-6 animate-in fade-in duration-300">
      {/* Top Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-white">Job Openings & Requisitions</h1>
          <p className="text-xs text-slate-400 mt-1">
            Manage organization roles, revision histories, and 60/20/20 candidate matching.
          </p>
        </div>

        <Button
          variant="primary"
          size="sm"
          onClick={() => setIsCreateModalOpen(true)}
          icon={<Plus className="w-4 h-4" />}
        >
          Post New Opening
        </Button>
      </div>

      {/* Jobs Grid */}
      {isLoading ? (
        <div className="py-20 text-center text-slate-500 text-xs">
          Loading active requisitions...
        </div>
      ) : jobs.length === 0 ? (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-12 text-center">
          <Briefcase className="w-10 h-10 text-slate-600 mx-auto mb-3" />
          <h3 className="text-sm font-semibold text-white">No Open Positions</h3>
          <p className="text-xs text-slate-400 mt-1 max-w-sm mx-auto">
            Get started by posting your first job description to begin receiving candidates.
          </p>
          <Button
            variant="primary"
            size="sm"
            className="mt-4"
            onClick={() => setIsCreateModalOpen(true)}
          >
            Create First Requisition
          </Button>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          {jobs.map((job) => (
            <div
              key={job.id}
              className="bg-slate-900 border border-slate-800 rounded-2xl p-5 flex flex-col justify-between hover:border-slate-700 transition-colors shadow-lg"
            >
              <div>
                <div className="flex items-start justify-between gap-2">
                  <Badge variant={job.status === 'ACTIVE' ? 'emerald' : 'slate'} size="sm" dot>
                    {job.status}
                  </Badge>
                  <button
                    onClick={() => handleToggleStatus(job)}
                    className="text-[11px] text-slate-400 hover:text-white transition-colors"
                  >
                    {job.status === 'ACTIVE' ? 'Close' : 'Reopen'}
                  </button>
                </div>

                <h3 className="text-base font-bold text-white mt-3 leading-snug line-clamp-1">
                  {job.title}
                </h3>

                <div className="flex flex-wrap items-center gap-3 mt-2 text-xs text-slate-400">
                  <span className="flex items-center gap-1">
                    <Building className="w-3.5 h-3.5 text-slate-500" />
                    {job.department || 'Engineering'}
                  </span>
                  <span className="flex items-center gap-1">
                    <MapPin className="w-3.5 h-3.5 text-slate-500" />
                    {job.location || 'Remote'}
                  </span>
                </div>

                <p className="text-xs text-slate-400 mt-3 line-clamp-2 leading-relaxed">
                  {job.description || job.rawJobDescription || 'Full-cycle engineering recruitment role.'}
                </p>

                <div className="mt-4 pt-3 border-t border-slate-800/80 flex items-center justify-between text-[11px] text-slate-500">
                  <span className="flex items-center gap-1">
                    <Layers className="w-3.5 h-3.5 text-slate-500" />
                    {job.versionCount || 1} revision(s)
                  </span>
                  <button
                    onClick={() => {
                      setRevisionJob(job);
                      setRevisionDesc(job.description || '');
                    }}
                    className="text-emerald-400 hover:underline"
                  >
                    Update Requirements
                  </button>
                </div>
              </div>

              <div className="mt-5 pt-3 border-t border-slate-800">
                <Button
                  variant="outline"
                  size="sm"
                  className="w-full justify-between"
                  onClick={() => handleOpenMatches(job)}
                >
                  <span className="flex items-center gap-1.5">
                    <Sparkles className="w-3.5 h-3.5 text-emerald-400" />
                    Candidate Matches
                  </span>
                  <ChevronRight className="w-4 h-4 text-slate-500" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Create Job Modal */}
      <Modal
        isOpen={isCreateModalOpen}
        onClose={() => setIsCreateModalOpen(false)}
        title="Post New Requisition"
        subtitle="Specify candidate experience guidelines and job requirements"
        maxWidth="2xl"
      >
        <form onSubmit={handleCreateJob} className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-slate-300 mb-1">
              Job Title *
            </label>
            <input
              type="text"
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              placeholder="e.g. Lead Distributed Systems Engineer"
              className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500"
              required
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                Department
              </label>
              <input
                type="text"
                value={newDepartment}
                onChange={(e) => setNewDepartment(e.target.value)}
                placeholder="e.g. Core Platform"
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                Location
              </label>
              <input
                type="text"
                value={newLocation}
                onChange={(e) => setNewLocation(e.target.value)}
                placeholder="e.g. Bengaluru / Hybrid"
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                Min Experience (Months)
              </label>
              <input
                type="number"
                value={newMinExp}
                onChange={(e) => setNewMinExp(Number(e.target.value))}
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                Max Experience (Months)
              </label>
              <input
                type="number"
                value={newMaxExp}
                onChange={(e) => setNewMaxExp(Number(e.target.value))}
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-300 mb-1">
              Job Description & Skill Requirements *
            </label>
            <textarea
              value={newDescription}
              onChange={(e) => setNewDescription(e.target.value)}
              placeholder="Paste raw requisition or required tech stack (Java, Spring Boot, Postgres, Kafka...)"
              rows={6}
              className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500 leading-relaxed font-mono"
              required
            />
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
              isLoading={isSubmittingJob}
            >
              Create Position
            </Button>
          </div>
        </form>
      </Modal>

      {/* Revision Modal */}
      {revisionJob && (
        <Modal
          isOpen={true}
          onClose={() => setRevisionJob(null)}
          title={`Publish New Revision: ${revisionJob.title}`}
          subtitle="Updates skill weights and re-triggers matching algorithm"
          maxWidth="2xl"
        >
          <form onSubmit={handleCreateRevision} className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">
                Updated Job Description
              </label>
              <textarea
                value={revisionDesc}
                onChange={(e) => setRevisionDesc(e.target.value)}
                rows={8}
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-white focus:outline-none focus:border-emerald-500 font-mono leading-relaxed"
                required
              />
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setRevisionJob(null)}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                variant="primary"
                size="sm"
                isLoading={isSubmittingRevision}
              >
                Publish Revision
              </Button>
            </div>
          </form>
        </Modal>
      )}

      {/* Candidate Matches Drawer */}
      <Drawer
        isOpen={!!matchJob}
        onClose={() => setMatchJob(null)}
        title={matchJob ? `Ranked Matches: ${matchJob.title}` : 'Matches'}
        subtitle="60% Skill Overlap + 20% Experience Fit + 20% Role Alignment"
        width="2xl"
      >
        {isMatchLoading ? (
          <div className="py-20 text-center text-slate-500 text-xs">
            Evaluating candidate database against requisition requirements...
          </div>
        ) : matches.length === 0 ? (
          <div className="py-16 text-center text-slate-500 text-xs">
            <Sparkles className="w-8 h-8 text-slate-600 mx-auto mb-2" />
            No matching candidates found for this role yet.
          </div>
        ) : (
          <div className="space-y-4">
            {matches.map((m, idx) => (
              <div
                key={m.candidateId || idx}
                className="p-4 bg-slate-950 border border-slate-800 rounded-xl hover:border-slate-700 transition-colors"
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="w-8 h-8 rounded-lg bg-emerald-950/60 border border-emerald-800 text-emerald-400 font-bold text-xs flex items-center justify-center">
                      #{idx + 1}
                    </div>
                    <div>
                      <h4 className="font-semibold text-white text-xs">{m.candidateName}</h4>
                      <span className="text-[11px] text-slate-500">{m.candidateEmail}</span>
                    </div>
                  </div>
                  <Badge
                    variant={m.overallScore >= 70 ? 'emerald' : m.overallScore >= 50 ? 'amber' : 'slate'}
                    size="sm"
                  >
                    {m.overallScore}% Match
                  </Badge>
                </div>

                {/* Score Breakdown Bars */}
                <div className="grid grid-cols-3 gap-2 mt-4 pt-3 border-t border-slate-900 text-[11px]">
                  <div>
                    <span className="text-slate-500 block text-[10px]">Skills (60%)</span>
                    <span className="text-slate-200 font-semibold">{m.skillScore ?? '—'}%</span>
                  </div>
                  <div>
                    <span className="text-slate-500 block text-[10px]">Exp (20%)</span>
                    <span className="text-slate-200 font-semibold">{m.experienceScore ?? '—'}%</span>
                  </div>
                  <div>
                    <span className="text-slate-500 block text-[10px]">Role (20%)</span>
                    <span className="text-slate-200 font-semibold">{m.roleScore ?? '—'}%</span>
                  </div>
                </div>

                {/* Skill tags */}
                {m.matchingSkills && m.matchingSkills.length > 0 && (
                  <div className="mt-3 flex flex-wrap gap-1">
                    {m.matchingSkills.map((s, i) => (
                      <span
                        key={i}
                        className="px-2 py-0.5 bg-emerald-950/40 border border-emerald-800/40 text-emerald-400 rounded text-[10px]"
                      >
                        ✓ {s}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </Drawer>
    </div>
  );
};
