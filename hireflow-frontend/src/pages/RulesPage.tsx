import React, { useEffect, useState } from 'react';
import {
  Sliders,
  Plus,
  Play,
  ArrowUp,
  ArrowDown,
  Trash2,
  Edit2,
  CheckCircle,
  XCircle,
  HelpCircle,
  AlertTriangle,
  Briefcase,
  FileCheck,
  Check,
} from 'lucide-react';
import { api } from '../services/api';
import { EmailRuleDto, RulePreviewRequest, RulePreviewResponse, JobDto, MailConnectionDto } from '../types';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { useToast } from '../components/common/Toast';

export const RulesPage: React.FC = () => {
  const { toast } = useToast();

  const [rules, setRules] = useState<EmailRuleDto[]>([]);
  const [jobs, setJobs] = useState<JobDto[]>([]);
  const [connections, setConnections] = useState<MailConnectionDto[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isReordering, setIsReordering] = useState(false);

  // Create/Edit Rule Modal state
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<EmailRuleDto | null>(null);
  const [ruleName, setRuleName] = useState('');
  const [ruleDescription, setRuleDescription] = useState('');
  const [rulePriority, setRulePriority] = useState(10);
  const [ruleMailboxId, setRuleMailboxId] = useState<string>('');
  const [ruleFolder, setRuleFolder] = useState('');
  const [ruleSenderInclude, setRuleSenderInclude] = useState('');
  const [ruleSenderExclude, setRuleSenderExclude] = useState('');
  const [ruleSubjectKeywords, setRuleSubjectKeywords] = useState('');
  const [ruleBodyKeywords, setRuleBodyKeywords] = useState('');
  const [ruleRequireAttachment, setRuleRequireAttachment] = useState(true);
  const [ruleAllowedMimes, setRuleAllowedMimes] = useState('application/pdf, application/vnd.openxmlformats-officedocument.wordprocessingml.document');
  const [ruleTargetJobId, setRuleTargetJobId] = useState('');
  const [ruleDuplicatePolicy, setRuleDuplicatePolicy] = useState('UPDATE_PROFILE_ONLY');
  const [isSavingRule, setIsSavingRule] = useState(false);

  // Simulator state
  const [simSender, setSimSender] = useState('applicant@university.edu');
  const [simSubject, setSimSubject] = useState('Application for Lead Backend Engineer - Resume Attached');
  const [simBody, setSimBody] = useState('Hello Team, I am submitting my resume for the Distributed Systems Engineer role.');
  const [simFilename, setSimFilename] = useState('John_Doe_CV.pdf');
  const [simMime, setSimMime] = useState('application/pdf');
  const [previewResult, setPreviewResult] = useState<RulePreviewResponse | null>(null);
  const [isPreviewing, setIsPreviewing] = useState(false);

  const loadData = async () => {
    setIsLoading(true);
    try {
      const [rulesData, jobsData, connsData] = await Promise.all([
        api.getRules(),
        api.getJobs().catch(() => []),
        api.getMailConnections().catch(() => []),
      ]);
      setRules(rulesData || []);
      setJobs(jobsData || []);
      setConnections(connsData || []);
    } catch (err: any) {
      toast('Failed to load rules', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleOpenCreateModal = (ruleToEdit?: EmailRuleDto) => {
    if (ruleToEdit) {
      setEditingRule(ruleToEdit);
      setRuleName(ruleToEdit.name);
      setRuleDescription(ruleToEdit.description || '');
      setRulePriority(ruleToEdit.priority);
      setRuleMailboxId(ruleToEdit.connectionId || '');
      setRuleFolder(ruleToEdit.folderFilter || '');
      setRuleSenderInclude((ruleToEdit.senderInclude || []).join(', '));
      setRuleSenderExclude((ruleToEdit.senderExclude || []).join(', '));
      setRuleSubjectKeywords((ruleToEdit.subjectKeywords || []).join(', '));
      setRuleBodyKeywords((ruleToEdit.bodyKeywords || []).join(', '));
      setRuleRequireAttachment(ruleToEdit.requireAttachment ?? true);
      setRuleAllowedMimes((ruleToEdit.allowedMimes || []).join(', '));
      setRuleTargetJobId(ruleToEdit.targetJobId || '');
      setRuleDuplicatePolicy(ruleToEdit.duplicatePolicy || 'UPDATE_PROFILE_ONLY');
    } else {
      setEditingRule(null);
      setRuleName('');
      setRuleDescription('');
      setRulePriority((rules.length + 1) * 10);
      setRuleMailboxId('');
      setRuleFolder('');
      setRuleSenderInclude('');
      setRuleSenderExclude('');
      setRuleSubjectKeywords('resume, application, cv');
      setRuleBodyKeywords('');
      setRuleRequireAttachment(true);
      setRuleAllowedMimes('application/pdf, application/vnd.openxmlformats-officedocument.wordprocessingml.document');
      setRuleTargetJobId('');
      setRuleDuplicatePolicy('UPDATE_PROFILE_ONLY');
    }
    setIsModalOpen(true);
  };

  const handleMoveRule = async (currentIndex: number, direction: -1 | 1) => {
    const targetIndex = currentIndex + direction;
    if (targetIndex < 0 || targetIndex >= rules.length) return;

    // Optimistically reorder in local state
    const newRules = [...rules];
    const [movedRule] = newRules.splice(currentIndex, 1);
    newRules.splice(targetIndex, 0, movedRule);
    setRules(newRules);
    setIsReordering(true);

    try {
      const ruleIds = newRules.map((r) => r.id);
      await api.reorderRules(ruleIds);
      toast('Rule order updated successfully', 'success');
      loadData();
    } catch (err: any) {
      toast(err.message || 'Failed to reorder rules', 'error');
      loadData();
    } finally {
      setIsReordering(false);
    }
  };

  const parseCommaList = (str: string) =>
    str
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);

  const handleSaveRule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!ruleName.trim()) {
      toast('Rule name is required', 'error');
      return;
    }
    setIsSavingRule(true);
    try {
      const payload: Partial<EmailRuleDto> = {
        name: ruleName.trim(),
        description: ruleDescription.trim() || undefined,
        priority: Number(rulePriority),
        connectionId: ruleMailboxId || null,
        folderFilter: ruleFolder.trim() || null,
        senderInclude: parseCommaList(ruleSenderInclude),
        senderExclude: parseCommaList(ruleSenderExclude),
        subjectKeywords: parseCommaList(ruleSubjectKeywords),
        bodyKeywords: parseCommaList(ruleBodyKeywords),
        requireAttachment: ruleRequireAttachment,
        allowedMimes: parseCommaList(ruleAllowedMimes),
        targetJobId: ruleTargetJobId || null,
        duplicatePolicy: ruleDuplicatePolicy,
      };

      if (editingRule) {
        await api.updateRule(editingRule.id, payload);
        toast(`Updated rule "${ruleName}"`, 'success');
      } else {
        await api.createRule(payload);
        toast(`Created rule "${ruleName}"`, 'success');
      }
      setIsModalOpen(false);
      loadData();
    } catch (err: any) {
      toast(err.message || 'Failed to save rule', 'error');
    } finally {
      setIsSavingRule(false);
    }
  };

  const handleToggleStatus = async (rule: EmailRuleDto) => {
    try {
      await api.toggleRuleStatus(rule.id, !rule.enabled);
      toast(`Rule ${rule.name} is now ${!rule.enabled ? 'Enabled' : 'Disabled'}`, 'success');
      loadData();
    } catch (err: any) {
      toast('Failed to toggle rule status', 'error');
    }
  };

  const handleDeleteRule = async (rule: EmailRuleDto) => {
    if (!confirm(`Delete rule "${rule.name}"?`)) return;
    try {
      await api.deleteRule(rule.id);
      toast(`Rule deleted`, 'success');
      loadData();
    } catch (err: any) {
      toast('Failed to delete rule', 'error');
    }
  };

  const handleRunSimulator = async () => {
    setIsPreviewing(true);
    try {
      const payload: RulePreviewRequest = {
        sender: simSender,
        senderEmail: simSender,
        subject: simSubject,
        body: simBody,
        bodySnippet: simBody,
        attachments: [
          {
            filename: simFilename,
            mimeType: simMime,
            sizeBytes: 154200,
          },
        ],
      };
      const res = await api.previewRule(payload);
      setPreviewResult(res);
      toast('Rule dry-run evaluation complete', 'success');
    } catch (err: any) {
      toast(err.message || 'Preview evaluation failed', 'error');
    } finally {
      setIsPreviewing(false);
    }
  };

  return (
    <div className="space-y-8 animate-in fade-in duration-300">
      {/* Top Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-white">Recruitment Email Rules</h1>
          <p className="text-xs text-slate-400 mt-1">
            Deterministic include/exclude filtering, folder routing, and automated job mapping.
          </p>
        </div>

        <Button
          variant="primary"
          size="sm"
          onClick={() => handleOpenCreateModal()}
          icon={<Plus className="w-4 h-4" />}
        >
          Create Ingestion Rule
        </Button>
      </div>

      {/* Rules Table */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden shadow-xl">
        <div className="p-4 border-b border-slate-800 flex items-center justify-between">
          <span className="text-xs font-semibold text-white">Active Rule Order (Priority ASC)</span>
          <span className="text-[11px] text-slate-500">First matching rule governs applicant ingestion</span>
        </div>

        <div className="divide-y divide-slate-800/60">
          {isLoading ? (
            <div className="py-16 text-center text-slate-500 text-xs">Loading rules...</div>
          ) : rules.length === 0 ? (
            <div className="py-16 text-center text-slate-500 text-xs">
              No recruitment rules configured. Click "Create Ingestion Rule" above.
            </div>
          ) : (
            rules.map((rule, idx) => (
              <div
                key={rule.id}
                className="p-4 flex flex-col md:flex-row md:items-center justify-between gap-4 hover:bg-slate-800/30 transition-colors"
              >
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 rounded-lg bg-slate-800 border border-slate-700 flex items-center justify-center font-bold text-xs text-slate-300 shrink-0 mt-0.5">
                    #{idx + 1}
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-white text-xs">{rule.name}</span>
                      <Badge variant={rule.enabled ? 'emerald' : 'slate'} size="sm" dot>
                        {rule.enabled ? 'Active' : 'Disabled'}
                      </Badge>
                    </div>

                    <p className="text-[11px] text-slate-400 mt-1 line-clamp-1">
                      {rule.description || 'Applies to inbound messages.'}
                    </p>

                    <div className="flex flex-wrap items-center gap-2 mt-2 text-[10px] text-slate-500">
                      {rule.subjectKeywords && rule.subjectKeywords.length > 0 && (
                        <span className="bg-slate-950 border border-slate-800 px-2 py-0.5 rounded">
                          Keywords: {rule.subjectKeywords.slice(0, 3).join(', ')}
                        </span>
                      )}
                      {rule.targetJobId && (
                        <span className="bg-indigo-950/60 border border-indigo-800/60 text-indigo-400 px-2 py-0.5 rounded">
                          Target Job Mapped
                        </span>
                      )}
                      <span className="bg-slate-950 border border-slate-800 px-2 py-0.5 rounded">
                        Policy: {rule.duplicatePolicy}
                      </span>
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-2 self-end md:self-center">
                  {/* Priority Reordering Buttons */}
                  <div className="flex items-center border border-slate-800 rounded-lg p-0.5 bg-slate-950">
                    <button
                      type="button"
                      disabled={idx === 0 || isReordering}
                      onClick={() => handleMoveRule(idx, -1)}
                      className="p-1.5 rounded text-slate-400 hover:text-white hover:bg-slate-800 disabled:opacity-25 disabled:cursor-not-allowed transition-colors"
                      title="Move up (increase priority)"
                      aria-label={`Move ${rule.name} up`}
                    >
                      <ArrowUp className="w-3.5 h-3.5" />
                    </button>
                    <button
                      type="button"
                      disabled={idx === rules.length - 1 || isReordering}
                      onClick={() => handleMoveRule(idx, 1)}
                      className="p-1.5 rounded text-slate-400 hover:text-white hover:bg-slate-800 disabled:opacity-25 disabled:cursor-not-allowed transition-colors"
                      title="Move down (decrease priority)"
                      aria-label={`Move ${rule.name} down`}
                    >
                      <ArrowDown className="w-3.5 h-3.5" />
                    </button>
                  </div>

                  <button
                    onClick={() => handleToggleStatus(rule)}
                    className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors ${
                      rule.enabled
                        ? 'border-emerald-800 text-emerald-400 hover:bg-emerald-950/40'
                        : 'border-slate-700 text-slate-400 hover:bg-slate-800'
                    }`}
                  >
                    {rule.enabled ? 'Disable' : 'Enable'}
                  </button>

                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleOpenCreateModal(rule)}
                  >
                    <Edit2 className="w-3.5 h-3.5" />
                  </Button>

                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleDeleteRule(rule)}
                    className="text-slate-500 hover:text-rose-400"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </Button>
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      {/* Rule Preview & Test Simulator */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h3 className="text-base font-bold text-white flex items-center gap-2">
              <Play className="w-4 h-4 text-emerald-400" />
              Rule Preview Simulator
            </h3>
            <p className="text-xs text-slate-400 mt-0.5">
              Dry-run evaluate a hypothetical message against all active rules without ingesting data.
            </p>
          </div>

          <Button
            variant="primary"
            size="sm"
            onClick={handleRunSimulator}
            isLoading={isPreviewing}
            icon={<Play className="w-3.5 h-3.5" />}
          >
            Run Dry-Run Preview
          </Button>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          {/* Sample Payload Form */}
          <div className="space-y-3 bg-slate-950/60 border border-slate-800 p-4 rounded-xl text-xs">
            <div>
              <label className="block text-[11px] text-slate-400 mb-1">Simulated Sender Email</label>
              <input
                type="text"
                value={simSender}
                onChange={(e) => setSimSender(e.target.value)}
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
              />
            </div>

            <div>
              <label className="block text-[11px] text-slate-400 mb-1">Simulated Subject Line</label>
              <input
                type="text"
                value={simSubject}
                onChange={(e) => setSimSubject(e.target.value)}
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
              />
            </div>

            <div>
              <label className="block text-[11px] text-slate-400 mb-1">Simulated Attachment Filename</label>
              <input
                type="text"
                value={simFilename}
                onChange={(e) => setSimFilename(e.target.value)}
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
          </div>

          {/* Evaluation Outcome Display */}
          <div className="bg-slate-950/60 border border-slate-800 p-4 rounded-xl text-xs flex flex-col justify-between">
            <div>
              <span className="text-[11px] font-semibold text-slate-400 block mb-3 uppercase tracking-wider">
                Evaluation Decision Breakdown
              </span>

              {previewResult ? (
                <div className="space-y-3">
                  <div className="flex items-center justify-between p-3 bg-slate-900 border border-slate-800 rounded-lg">
                    <span className="text-slate-400">Matched Rule:</span>
                    <span className="font-semibold text-emerald-400">
                      {previewResult.matchedRuleName || 'No Rule Matched (Default Fallback)'}
                    </span>
                  </div>

                  <div className="flex items-center justify-between p-3 bg-slate-900 border border-slate-800 rounded-lg">
                    <span className="text-slate-400">Selected Requisition:</span>
                    <span className="font-semibold text-white">
                      {previewResult.targetJobTitle || (previewResult.targetJobId ? 'Job #' + previewResult.targetJobId.slice(0, 8) : 'Auto-match')}
                    </span>
                  </div>

                  <div className="flex items-center justify-between p-3 bg-slate-900 border border-slate-800 rounded-lg">
                    <span className="text-slate-400">Review Queue Routing:</span>
                    <Badge variant={previewResult.routeToReview ? 'amber' : 'emerald'} size="sm">
                      {previewResult.routeToReview ? 'YES (Conflict/Ambiguity)' : 'NO (Direct Process)'}
                    </Badge>
                  </div>

                  {previewResult.explanation && (
                    <div className="p-3 bg-slate-900/60 border border-slate-800 rounded-lg text-[11px] text-slate-300">
                      {previewResult.explanation}
                    </div>
                  )}
                </div>
              ) : (
                <div className="py-12 text-center text-slate-600 text-xs">
                  Run simulation to verify rule condition matching and action resolution.
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Create / Edit Rule Modal */}
      <Modal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title={editingRule ? `Edit Rule: ${editingRule.name}` : 'Create Recruitment Rule'}
        subtitle="Deterministic filtering for incoming applicant emails"
        maxWidth="2xl"
      >
        <form onSubmit={handleSaveRule} className="space-y-4">
          <div className="grid grid-cols-3 gap-3">
            <div className="col-span-2">
              <label className="block text-xs font-medium text-slate-300 mb-1">Rule Name *</label>
              <input
                type="text"
                value={ruleName}
                onChange={(e) => setRuleName(e.target.value)}
                placeholder="e.g. Campus Engineering Ingestion"
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
                required
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">Priority Index</label>
              <input
                type="number"
                value={rulePriority}
                onChange={(e) => setRulePriority(Number(e.target.value))}
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">Target Mailbox Scope</label>
              <select
                value={ruleMailboxId}
                onChange={(e) => setRuleMailboxId(e.target.value)}
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
              >
                <option value="">All Mailboxes (Global)</option>
                {connections.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.emailAddress} ({c.provider})
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">Folder / Label Filter</label>
              <input
                type="text"
                value={ruleFolder}
                onChange={(e) => setRuleFolder(e.target.value)}
                placeholder="e.g. INBOX, Careers"
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">Sender Include (comma-separated)</label>
              <input
                type="text"
                value={ruleSenderInclude}
                onChange={(e) => setRuleSenderInclude(e.target.value)}
                placeholder="*@mit.edu, *@iit.ac.in"
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">Sender Exclude</label>
              <input
                type="text"
                value={ruleSenderExclude}
                onChange={(e) => setRuleSenderExclude(e.target.value)}
                placeholder="*no-reply*, *newsletter*"
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-300 mb-1">Subject Keywords (comma-separated)</label>
            <input
              type="text"
              value={ruleSubjectKeywords}
              onChange={(e) => setRuleSubjectKeywords(e.target.value)}
              placeholder="resume, job application, cv"
              className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">Target Job Mapping</label>
              <select
                value={ruleTargetJobId}
                onChange={(e) => setRuleTargetJobId(e.target.value)}
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
              >
                <option value="">Auto-match Open Jobs (or Review Queue)</option>
                {jobs.map((j) => (
                  <option key={j.id} value={j.id}>
                    {j.title}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1">Duplicate Policy</label>
              <select
                value={ruleDuplicatePolicy}
                onChange={(e) => setRuleDuplicatePolicy(e.target.value)}
                className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
              >
                <option value="UPDATE_PROFILE_ONLY">UPDATE_PROFILE_ONLY</option>
                <option value="REPLACE_PROFILE">REPLACE_PROFILE</option>
                <option value="NEW_APPLICATION">NEW_APPLICATION</option>
                <option value="SKIP_DUPLICATE">SKIP_DUPLICATE</option>
              </select>
            </div>
          </div>

          <div className="flex items-center gap-2 pt-2">
            <input
              type="checkbox"
              id="reqAtt"
              checked={ruleRequireAttachment}
              onChange={(e) => setRuleRequireAttachment(e.target.checked)}
              className="rounded bg-slate-950 border-slate-800 text-emerald-500 focus:ring-emerald-500"
            />
            <label htmlFor="reqAtt" className="text-xs text-slate-300">
              Require valid PDF/DOCX attachment to match rule
            </label>
          </div>

          <div className="flex justify-end gap-2 pt-3">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setIsModalOpen(false)}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              size="sm"
              isLoading={isSavingRule}
            >
              Save Rule Configuration
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
};
