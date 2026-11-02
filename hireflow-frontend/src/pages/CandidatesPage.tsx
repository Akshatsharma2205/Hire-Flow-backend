import React, { useEffect, useState } from 'react';
import {
  Search,
  Filter,
  FileText,
  Mail,
  Phone,
  MapPin,
  Briefcase,
  ChevronRight,
  ExternalLink,
  Code,
  Calendar,
  Layers,
  Sparkles,
} from 'lucide-react';
import { api } from '../services/api';
import { Candidate, ResumeVersionDto, ApplicationDto, JobDto } from '../types';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { Drawer } from '../components/common/Drawer';
import { useToast } from '../components/common/Toast';

const AVATAR_PALETTES = [
  'bg-emerald-50 border-emerald-200 text-emerald-700 dark:bg-emerald-950/60 dark:border-emerald-800 dark:text-emerald-300',
  'bg-indigo-50 border-indigo-200 text-indigo-700 dark:bg-indigo-950/60 dark:border-indigo-800 dark:text-indigo-300',
  'bg-purple-50 border-purple-200 text-purple-700 dark:bg-purple-950/60 dark:border-purple-800 dark:text-purple-300',
  'bg-amber-50 border-amber-200 text-amber-700 dark:bg-amber-950/60 dark:border-amber-800 dark:text-amber-300',
  'bg-rose-50 border-rose-200 text-rose-700 dark:bg-rose-950/60 dark:border-rose-800 dark:text-rose-300',
  'bg-cyan-50 border-cyan-200 text-cyan-700 dark:bg-cyan-950/60 dark:border-cyan-800 dark:text-cyan-300',
  'bg-teal-50 border-teal-200 text-teal-700 dark:bg-teal-950/60 dark:border-teal-800 dark:text-teal-300',
];

const getAvatarColor = (nameOrEmail: string) => {
  let hash = 0;
  for (let i = 0; i < nameOrEmail.length; i++) {
    hash = nameOrEmail.charCodeAt(i) + ((hash << 5) - hash);
  }
  return AVATAR_PALETTES[Math.abs(hash) % AVATAR_PALETTES.length];
};

export const CandidatesPage: React.FC = () => {
  const { toast } = useToast();

  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [jobs, setJobs] = useState<JobDto[]>([]);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [currentPage, setCurrentPage] = useState(0);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedJobFilter, setSelectedJobFilter] = useState('');
  const [selectedChannelFilter, setSelectedChannelFilter] = useState('');
  const [quickFilter, setQuickFilter] = useState<'ALL' | 'HIGH_FIT' | 'UNRANKED' | 'UPLOAD' | 'EMAIL'>('ALL');
  const [isLoading, setIsLoading] = useState(true);

  // Detail Drawer state
  const [selectedCandidate, setSelectedCandidate] = useState<Candidate | null>(null);
  const [candidateVersions, setCandidateVersions] = useState<ResumeVersionDto[]>([]);
  const [candidateApplications, setCandidateApplications] = useState<ApplicationDto[]>([]);
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(null);
  const [pdfViewUrl, setPdfViewUrl] = useState<string | null>(null);
  const [isDrawerLoading, setIsDrawerLoading] = useState(false);
  const [showEvidence, setShowEvidence] = useState(false);

  const loadCandidates = async (
    page = 0,
    query = searchQuery,
    jobFilter = selectedJobFilter,
    channelFilter = selectedChannelFilter
  ) => {
    setIsLoading(true);
    try {
      const data = await api.getCandidates(
        page,
        15,
        query,
        jobFilter || undefined,
        channelFilter || undefined
      );
      setCandidates(data.content || []);
      setTotalPages(data.totalPages || 1);
      setTotalElements(data.totalElements || 0);
      setCurrentPage(page);
    } catch (err: any) {
      toast(err.message || 'Failed to fetch candidate directory', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    api.getJobs().then(setJobs).catch(() => {});
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => {
      loadCandidates(0, searchQuery, selectedJobFilter, selectedChannelFilter);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchQuery, selectedJobFilter, selectedChannelFilter]);

  const handleOpenDrawer = async (candidate: Candidate) => {
    setSelectedCandidate(candidate);
    setIsDrawerLoading(true);
    setPdfViewUrl(null);
    setShowEvidence(false);
    try {
      const [versions, apps] = await Promise.all([
        api.getCandidateVersions(candidate.id).catch(() => []),
        api.getCandidateApplications(candidate.id).catch(() => []),
      ]);
      setCandidateVersions(versions);
      setCandidateApplications(apps);

      const activeVersion =
        versions.find((v) => v.id === candidate.currentResumeVersionId) || versions[0];

      if (activeVersion) {
        setSelectedVersionId(activeVersion.id);
        loadPdfUrl(activeVersion.id);
      }
    } catch (err: any) {
      toast('Failed to load candidate profile details', 'error');
    } finally {
      setIsDrawerLoading(false);
    }
  };

  const loadPdfUrl = async (versionId: string) => {
    try {
      const res = await api.getResumeViewUrl(versionId);
      setPdfViewUrl(res.url);
    } catch (err) {
      setPdfViewUrl(null);
    }
  };

  const filteredCandidates = candidates.filter((c) => {
    const channel = c.sourceType || c.sourceChannel;
    if (selectedChannelFilter && channel && channel !== selectedChannelFilter) return false;
    if (quickFilter === 'HIGH_FIT' && (c.matchScore == null || c.matchScore < 70)) return false;
    if (quickFilter === 'UNRANKED' && c.matchScore != null) return false;
    if (quickFilter === 'UPLOAD' && channel !== 'UPLOAD') return false;
    if (quickFilter === 'EMAIL' && !['GMAIL_OAUTH', 'MS_GRAPH_OAUTH', 'IMAP'].includes(channel || '')) return false;
    return true;
  });

  return (
    <div className="space-y-6 animate-in fade-in duration-300">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <p className="eyebrow">Talent directory</p>
          <h1 className="mt-2 text-[26px] font-semibold leading-tight tracking-[-0.03em] text-slate-950 dark:text-slate-50">
            Candidate Intelligence
          </h1>
          <p className="mt-1.5 text-[12.5px] leading-6 text-slate-500 dark:text-slate-400">
            Living applicant profiles extracted across inbound mailboxes, referrals, and uploads.
          </p>
        </div>
        <div className="text-xs font-medium text-slate-500 dark:text-slate-400">
          Showing <span className="text-slate-900 dark:text-white font-semibold">{totalElements}</span> candidates
        </div>
      </div>

      {/* Quick Filter Badges */}
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => setQuickFilter('ALL')}
          className={`px-3 py-1.5 rounded-xl text-xs font-medium transition-all ${
            quickFilter === 'ALL'
              ? 'bg-teal-700 text-white shadow-sm dark:bg-teal-500 dark:text-slate-950 font-semibold'
              : 'bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-700'
          }`}
        >
          All Profiles
        </button>
        <button
          type="button"
          onClick={() => setQuickFilter('HIGH_FIT')}
          className={`px-3 py-1.5 rounded-xl text-xs font-medium transition-all flex items-center gap-1.5 ${
            quickFilter === 'HIGH_FIT'
              ? 'bg-emerald-700 text-white shadow-sm dark:bg-emerald-500 dark:text-slate-950 font-semibold'
              : 'bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-700'
          }`}
        >
          <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
          <span>Top Matches (&ge;70%)</span>
        </button>
        <button
          type="button"
          onClick={() => setQuickFilter('UNRANKED')}
          className={`px-3 py-1.5 rounded-xl text-xs font-medium transition-all ${
            quickFilter === 'UNRANKED'
              ? 'bg-slate-800 text-white shadow-sm dark:bg-slate-700 dark:text-white font-semibold'
              : 'bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-700'
          }`}
        >
          Unranked Profiles
        </button>
        <button
          type="button"
          onClick={() => setQuickFilter('EMAIL')}
          className={`px-3 py-1.5 rounded-xl text-xs font-medium transition-all ${
            quickFilter === 'EMAIL'
              ? 'bg-indigo-700 text-white shadow-sm dark:bg-indigo-500 dark:text-slate-950 font-semibold'
              : 'bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-700'
          }`}
        >
          Mailbox Ingested
        </button>
        <button
          type="button"
          onClick={() => setQuickFilter('UPLOAD')}
          className={`px-3 py-1.5 rounded-xl text-xs font-medium transition-all ${
            quickFilter === 'UPLOAD'
              ? 'bg-teal-700 text-white shadow-sm dark:bg-teal-500 dark:text-slate-950 font-semibold'
              : 'bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-700'
          }`}
        >
          Direct Uploads
        </button>
      </div>

      {/* Filter and Search Controls */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-4 rounded-2xl flex flex-wrap items-center gap-3 shadow-sm">
        <div className="relative flex-1 min-w-[240px]">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 dark:text-slate-500" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search by candidate name or email..."
            className="w-full pl-9 pr-4 py-2 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl text-xs text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 focus:outline-none focus:border-teal-600 dark:focus:border-teal-400"
          />
        </div>

        <select
          value={selectedChannelFilter}
          onChange={(e) => setSelectedChannelFilter(e.target.value)}
          className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-700 focus:border-teal-600 focus:outline-none dark:border-slate-800 dark:bg-slate-950 dark:text-slate-300 dark:focus:border-teal-400 sm:w-auto"
        >
          <option value="">All Source Channels</option>
          <option value="UPLOAD">Direct Upload</option>
          <option value="GMAIL_OAUTH">Google Workspace</option>
          <option value="MS_GRAPH_OAUTH">Microsoft 365</option>
          <option value="IMAP">Custom IMAP</option>
          <option value="EXTENSION">Chrome Extension</option>
        </select>

        <select
          value={selectedJobFilter}
          onChange={(e) => setSelectedJobFilter(e.target.value)}
          className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-700 focus:border-teal-600 focus:outline-none dark:border-slate-800 dark:bg-slate-950 dark:text-slate-300 dark:focus:border-teal-400 sm:w-auto"
        >
          <option value="">All Job Requisitions</option>
          {jobs.map((j) => (
            <option key={j.id} value={j.id}>
              {j.title}
            </option>
          ))}
        </select>
      </div>

      {/* Candidates Data Table */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl overflow-hidden shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-950/70 border-b border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 font-semibold uppercase tracking-wider text-[11px]">
                <th className="py-3.5 px-5">Candidate</th>
                <th className="py-3.5 px-4 hidden sm:table-cell">Experience</th>
                <th className="py-3.5 px-4 hidden md:table-cell">Primary Skills</th>
                <th className="py-3.5 px-4 hidden sm:table-cell">Channel</th>
                <th className="py-3.5 px-4 hidden sm:table-cell">Match %</th>
                <th className="py-3.5 px-5 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800/60">
              {isLoading ? (
                <tr>
                  <td colSpan={6} className="py-12 text-center text-slate-500">
                    Loading candidates...
                  </td>
                </tr>
              ) : filteredCandidates.length === 0 ? (
                <tr>
                  <td colSpan={6} className="py-16 text-center text-slate-500">
                    No candidate profiles found matching your search criteria.
                  </td>
                </tr>
              ) : (
                filteredCandidates.map((cand) => {
                  const expYears = cand.currentExperienceMonths
                    ? (cand.currentExperienceMonths / 12).toFixed(1) + ' yrs'
                    : 'N/A';
                  const matchScore = cand.matchScore != null ? cand.matchScore : null;

                  return (
                    <tr
                      key={cand.id}
                      onClick={() => handleOpenDrawer(cand)}
                      className="hover:bg-slate-50 dark:hover:bg-slate-800/40 cursor-pointer transition-colors group"
                    >
                      <td className="py-4 px-5">
                        <div className="flex items-center gap-3">
                          <div className={`w-9 h-9 rounded-xl border font-bold flex items-center justify-center shrink-0 shadow-xs ${getAvatarColor(cand.displayName || cand.primaryEmail)}`}>
                            {cand.displayName ? cand.displayName.charAt(0).toUpperCase() : 'C'}
                          </div>
                          <div>
                            <span className="font-semibold text-slate-900 dark:text-white group-hover:text-teal-600 dark:group-hover:text-teal-400 transition-colors block">
                              {cand.displayName || 'Unnamed Candidate'}
                            </span>
                            <span className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1 mt-0.5">
                              <Mail className="w-3 h-3 text-slate-400 dark:text-slate-500" />
                              {cand.primaryEmail}
                            </span>
                          </div>
                        </div>
                      </td>

                      <td className="py-4 px-4 hidden sm:table-cell font-medium text-slate-700 dark:text-slate-300">
                        {expYears}
                      </td>

                      <td className="py-4 px-4 hidden md:table-cell">
                        <div className="flex flex-wrap gap-1 max-w-xs">
                          {cand.topSkills && cand.topSkills.length > 0 ? (
                            cand.topSkills.slice(0, 3).map((s, i) => (
                              <span
                                key={i}
                                className="px-2 py-0.5 bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md text-[10px] text-slate-700 dark:text-slate-300"
                              >
                                {s}
                              </span>
                            ))
                          ) : (
                            <span className="text-slate-400 dark:text-slate-500 text-[11px]">General profile</span>
                          )}
                        </div>
                      </td>

                      <td className="py-4 px-4 hidden sm:table-cell">
                        <Badge variant="slate" size="sm">
                          {cand.sourceChannel || 'DIRECT'}
                        </Badge>
                      </td>

                      <td className="py-4 px-4 hidden sm:table-cell">
                        {matchScore !== null ? (
                          <Badge
                            variant={matchScore >= 70 ? 'emerald' : matchScore >= 50 ? 'amber' : 'slate'}
                            size="sm"
                          >
                            {matchScore}% fit
                          </Badge>
                        ) : (
                          <span className="text-slate-400 dark:text-slate-500 text-xs">Unranked</span>
                        )}
                      </td>

                      <td className="py-4 px-5 text-right">
                        <button className="text-slate-400 group-hover:text-teal-600 dark:group-hover:text-teal-400 p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors">
                          <ChevronRight className="w-4 h-4" />
                        </button>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination Bar */}
        <div className="px-6 py-4 bg-slate-50 dark:bg-slate-950/60 border-t border-slate-200 dark:border-slate-800 flex items-center justify-between text-xs text-slate-500 dark:text-slate-400">
          <span>
            Page {currentPage + 1} of {Math.max(1, totalPages)}
          </span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={currentPage <= 0}
              onClick={() => loadCandidates(currentPage - 1)}
            >
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={currentPage + 1 >= totalPages}
              onClick={() => loadCandidates(currentPage + 1)}
            >
              Next
            </Button>
          </div>
        </div>
      </div>

      {/* Candidate Profile Slide-Over Drawer */}
      <Drawer
        isOpen={!!selectedCandidate}
        onClose={() => setSelectedCandidate(null)}
        title={selectedCandidate?.displayName || 'Candidate Profile'}
        subtitle={selectedCandidate?.primaryEmail}
        width="3xl"
      >
        {selectedCandidate && (
          <div className="space-y-6">
            {/* Quick Contact & Metadata */}
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3 p-4 bg-slate-50 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 rounded-xl text-xs">
              <div>
                <span className="text-slate-500 dark:text-slate-400 block text-[11px]">Experience</span>
                <span className="font-semibold text-slate-800 dark:text-slate-200">
                  {selectedCandidate.currentExperienceMonths
                    ? (selectedCandidate.currentExperienceMonths / 12).toFixed(1) + ' years'
                    : 'Not specified'}
                </span>
              </div>
              <div>
                <span className="text-slate-500 dark:text-slate-400 block text-[11px]">Location</span>
                <span className="font-semibold text-slate-800 dark:text-slate-200">
                  {selectedCandidate.currentCity || 'Remote / Unknown'}
                </span>
              </div>
              <div>
                <span className="text-slate-500 dark:text-slate-400 block text-[11px]">Status</span>
                <Badge variant="emerald" size="sm" dot>
                  {selectedCandidate.status || 'ACTIVE'}
                </Badge>
              </div>
            </div>

            {/* Versions Selector */}
            {candidateVersions.length > 0 && (
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-semibold text-slate-900 dark:text-white flex items-center gap-1.5">
                    <Layers className="w-3.5 h-3.5 text-teal-600 dark:text-teal-400" />
                    Resume Document Versions ({candidateVersions.length})
                  </span>
                </div>
                <div className="flex flex-wrap gap-2">
                  {candidateVersions.map((v) => (
                    <button
                      key={v.id}
                      onClick={() => {
                        setSelectedVersionId(v.id);
                        loadPdfUrl(v.id);
                      }}
                      className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors flex items-center gap-1.5 ${
                        selectedVersionId === v.id
                          ? 'bg-teal-50 dark:bg-teal-950/80 border-teal-500 text-teal-700 dark:text-teal-300'
                          : 'bg-slate-50 dark:bg-slate-950 border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
                      }`}
                    >
                      <span>V{v.versionNumber}</span>
                      <span className="text-[10px] text-slate-500">
                        ({v.validationStatus})
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* Embedded Resume Viewer */}
            <div className="bg-white dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl overflow-hidden shadow-sm">
              <div className="px-4 py-3 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between bg-slate-50 dark:bg-slate-900/60">
                <span className="text-xs font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-2">
                  <FileText className="w-4 h-4 text-teal-600 dark:text-teal-400" />
                  Embedded Document Preview
                </span>
                {pdfViewUrl && (
                  <a
                    href={pdfViewUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="text-xs text-teal-600 dark:text-teal-400 hover:underline flex items-center gap-1"
                  >
                    <span>Open External</span>
                    <ExternalLink className="w-3 h-3" />
                  </a>
                )}
              </div>

              <div className="h-[420px] bg-slate-100 dark:bg-slate-900 flex items-center justify-center relative">
                {pdfViewUrl ? (
                  <iframe
                    src={pdfViewUrl}
                    title="Resume Document"
                    className="w-full h-full border-0"
                  />
                ) : (
                  <div className="text-center p-6 text-slate-400 dark:text-slate-500 text-xs">
                    <FileText className="w-10 h-10 mx-auto mb-2 opacity-50" />
                    <span>No preview stream available for this document revision.</span>
                  </div>
                )}
              </div>
            </div>

            {/* Associated Applications */}
            <div>
              <h3 className="text-xs font-semibold text-slate-900 dark:text-white mb-2">Requisition Applications</h3>
              {candidateApplications.length === 0 ? (
                <div className="p-4 bg-slate-50 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 rounded-xl text-xs text-slate-400 dark:text-slate-500">
                  Candidate has not been linked to an active job opening yet.
                </div>
              ) : (
                <div className="space-y-2">
                  {candidateApplications.map((app) => (
                    <div
                      key={app.id}
                      className="p-3 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl flex items-center justify-between text-xs"
                    >
                      <div>
                        <span className="font-semibold text-slate-900 dark:text-white block">
                          {app.jobTitle || 'General Engineering Role'}
                        </span>
                        <span className="text-[11px] text-slate-500">
                          Applied: {new Date(app.createdAt).toLocaleDateString()}
                        </span>
                      </div>
                      <Badge variant="indigo" size="sm">
                        {app.currentStage}
                      </Badge>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Collapsible Raw Source Evidence */}
            <div className="border border-slate-200 dark:border-slate-800 rounded-xl overflow-hidden bg-slate-50 dark:bg-slate-950/60">
              <button
                onClick={() => setShowEvidence(!showEvidence)}
                className="w-full px-4 py-3 flex items-center justify-between text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-900/60 transition-colors"
              >
                <span className="flex items-center gap-2">
                  <Code className="w-4 h-4 text-teal-600 dark:text-teal-400" />
                  Source Evidence & Extraction Payload
                </span>
                <span className="text-[11px] text-slate-500">
                  {showEvidence ? 'Hide' : 'Show JSON'}
                </span>
              </button>

              {showEvidence && (
                <div className="p-4 border-t border-slate-200 dark:border-slate-800 bg-slate-100 dark:bg-slate-950 overflow-x-auto">
                  <pre className="text-[11px] font-mono text-teal-700 dark:text-teal-400 whitespace-pre-wrap">
                    {JSON.stringify(
                      {
                        candidateId: selectedCandidate.id,
                        displayName: selectedCandidate.displayName,
                        primaryEmail: selectedCandidate.primaryEmail,
                        currentExperienceMonths: selectedCandidate.currentExperienceMonths,
                        versions: candidateVersions,
                        applications: candidateApplications,
                      },
                      null,
                      2
                    )}
                  </pre>
                </div>
              )}
            </div>
          </div>
        )}
      </Drawer>
    </div>
  );
};
