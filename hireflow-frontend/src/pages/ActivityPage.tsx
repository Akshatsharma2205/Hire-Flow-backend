import React, { useEffect, useState } from 'react';
import {
  Activity,
  CheckCircle2,
  AlertTriangle,
  Clock,
  RefreshCw,
  FileText,
  Mail,
  ChevronRight,
  RotateCcw,
  Sliders,
  Check,
} from 'lucide-react';
import { api } from '../services/api';
import { InboundMessageDto } from '../types';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { useToast } from '../components/common/Toast';

const PIPELINE_STAGES = [
  { id: 'DISCOVERED', label: '1. Discovered' },
  { id: 'DOWNLOADING', label: '2. Downloading' },
  { id: 'VALIDATING', label: '3. Validating' },
  { id: 'EXTRACTING', label: '4. Extracting' },
  { id: 'MATCHING', label: '5. Matching' },
  { id: 'COMPLETED', label: '6. Completed' },
];

export const ActivityPage: React.FC = () => {
  const { toast } = useToast();

  const [messages, setMessages] = useState<InboundMessageDto[]>([]);
  const [selectedStatus, setSelectedStatus] = useState<string>('ALL');
  const [isLoading, setIsLoading] = useState(true);
  const [isRetrying, setIsRetrying] = useState(false);

  // Detail / Inspector modal state
  const [inspectMessage, setInspectMessage] = useState<InboundMessageDto | null>(null);

  const loadMessages = async () => {
    setIsLoading(true);
    try {
      const data = await api.getInboundMessages(selectedStatus);
      setMessages(data || []);
    } catch (err: any) {
      toast('Failed to load processing telemetry', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadMessages();
  }, [selectedStatus]);

  const handleRetry = async (id: string) => {
    setIsRetrying(true);
    try {
      await api.retryMessage(id);
      toast('Message re-queued into processing pipeline', 'success');
      setInspectMessage(null);
      loadMessages();
    } catch (err: any) {
      toast(err.message || 'Retry failed', 'error');
    } finally {
      setIsRetrying(false);
    }
  };

  return (
    <div className="space-y-8 animate-in fade-in duration-300">
      {/* Top Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-white">Processing Activity & Lifecycle</h1>
          <p className="text-xs text-slate-400 mt-1">
            Real-time pipeline audit log, message intake states, and failure recovery.
          </p>
        </div>

        <Button
          variant="outline"
          size="sm"
          onClick={loadMessages}
          icon={<RefreshCw className="w-3.5 h-3.5" />}
        >
          Refresh Log
        </Button>
      </div>

      {/* Lifecycle Pipeline Flow Banner */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-lg overflow-x-auto">
        <span className="text-xs font-semibold text-slate-300 block mb-3 uppercase tracking-wider text-[10px]">
          Ingestion & Extraction State Machine
        </span>
        <div className="flex items-center justify-between min-w-[700px] gap-2">
          {PIPELINE_STAGES.map((st, i) => (
            <React.Fragment key={st.id}>
              <div className="flex items-center gap-2 p-3 bg-slate-950 border border-slate-800 rounded-xl flex-1 justify-center">
                <span className="w-2 h-2 rounded-full bg-emerald-400" />
                <span className="text-xs font-semibold text-slate-200">{st.label}</span>
              </div>
              {i < PIPELINE_STAGES.length - 1 && (
                <ChevronRight className="w-4 h-4 text-slate-600 shrink-0" />
              )}
            </React.Fragment>
          ))}
        </div>
      </div>

      {/* Filter and Log Table */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden shadow-xl">
        <div className="p-4 border-b border-slate-800 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Activity className="w-4 h-4 text-emerald-400" />
            <span className="text-xs font-semibold text-white">Inbound Message Telemetry</span>
          </div>

          <div className="flex items-center gap-2">
            <span className="text-[11px] text-slate-400">Filter Status:</span>
            <select
              value={selectedStatus}
              onChange={(e) => setSelectedStatus(e.target.value)}
              className="px-2.5 py-1 bg-slate-950 border border-slate-800 rounded-lg text-xs text-slate-300 focus:outline-none focus:border-emerald-500"
            >
              <option value="ALL">All Statuses</option>
              <option value="DISCOVERED">DISCOVERED</option>
              <option value="PENDING">PENDING</option>
              <option value="PROCESSING">PROCESSING</option>
              <option value="COMPLETED">COMPLETED</option>
              <option value="REVIEW_REQUIRED">REVIEW_REQUIRED</option>
              <option value="FAILED">FAILED</option>
            </select>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="bg-slate-950/70 border-b border-slate-800 text-slate-400 font-semibold uppercase tracking-wider text-[11px]">
                <th className="py-3.5 px-5">Sender</th>
                <th className="py-3.5 px-4">Subject</th>
                <th className="py-3.5 px-4">Source</th>
                <th className="py-3.5 px-4">Status</th>
                <th className="py-3.5 px-4">Received At</th>
                <th className="py-3.5 px-5 text-right">Inspect</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/60">
              {isLoading ? (
                <tr>
                  <td colSpan={6} className="py-16 text-center text-slate-500">
                    Retrieving activity stream...
                  </td>
                </tr>
              ) : messages.length === 0 ? (
                <tr>
                  <td colSpan={6} className="py-16 text-center text-slate-500">
                    No ingestion events matching selected filter.
                  </td>
                </tr>
              ) : (
                messages.map((msg) => (
                  <tr
                    key={msg.id}
                    onClick={() => setInspectMessage(msg)}
                    className="hover:bg-slate-800/40 cursor-pointer transition-colors group"
                  >
                    <td className="py-3.5 px-5">
                      <div className="font-semibold text-white group-hover:text-emerald-400 transition-colors">
                        {msg.senderName || msg.senderEmail || 'Inbound Mail'}
                      </div>
                      <div className="text-[11px] text-slate-500 font-mono">
                        {msg.senderEmail}
                      </div>
                    </td>

                    <td className="py-3.5 px-4 text-slate-300 max-w-xs truncate">
                      {msg.subject || '(No Subject)'}
                    </td>

                    <td className="py-3.5 px-4">
                      <Badge variant="slate" size="sm">
                        {msg.sourceType || 'EMAIL'}
                      </Badge>
                    </td>

                    <td className="py-3.5 px-4">
                      <Badge
                        variant={
                          msg.processingStatus === 'COMPLETED'
                            ? 'emerald'
                            : msg.processingStatus === 'FAILED'
                            ? 'rose'
                            : msg.processingStatus === 'REVIEW_REQUIRED'
                            ? 'amber'
                            : 'cyan'
                        }
                        size="sm"
                        dot
                      >
                        {msg.processingStatus}
                      </Badge>
                    </td>

                    <td className="py-3.5 px-4 text-slate-400 font-mono text-[11px]">
                      {msg.receivedAt
                        ? new Date(msg.receivedAt).toLocaleString()
                        : new Date(msg.createdAt).toLocaleString()}
                    </td>

                    <td className="py-3.5 px-5 text-right">
                      <button className="text-slate-400 group-hover:text-emerald-400 p-1.5 rounded-lg hover:bg-slate-800">
                        <ChevronRight className="w-4 h-4" />
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Message Inspector Modal */}
      {inspectMessage && (
        <Modal
          isOpen={true}
          onClose={() => setInspectMessage(null)}
          title="Message Extraction Inspector"
          subtitle={`Message ID: ${inspectMessage.id}`}
          maxWidth="2xl"
        >
          <div className="space-y-4">
            <div className="p-3 bg-slate-950 border border-slate-800 rounded-xl space-y-2 text-xs">
              <div className="flex justify-between">
                <span className="text-slate-500">Sender:</span>
                <span className="text-white font-mono">{inspectMessage.senderEmail}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Subject:</span>
                <span className="text-slate-200">{inspectMessage.subject}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Current Status:</span>
                <Badge
                  variant={
                    inspectMessage.processingStatus === 'COMPLETED'
                      ? 'emerald'
                      : inspectMessage.processingStatus === 'FAILED'
                      ? 'rose'
                      : 'amber'
                  }
                  size="sm"
                >
                  {inspectMessage.processingStatus}
                </Badge>
              </div>
            </div>

            {/* Rule Match Details */}
            {inspectMessage.ruleAction && (
              <div className="p-3 bg-slate-950 border border-slate-800 rounded-xl text-xs space-y-1">
                <span className="text-slate-500 block text-[11px] uppercase tracking-wider">
                  Rule Engine Evaluation
                </span>
                <div className="text-slate-300">
                  Action: <strong className="text-emerald-400">{inspectMessage.ruleAction}</strong>
                </div>
                {inspectMessage.ruleExplanation && (
                  <p className="text-slate-400 text-[11px] mt-1">
                    {inspectMessage.ruleExplanation}
                  </p>
                )}
              </div>
            )}

            {/* Failure Inspector */}
            {inspectMessage.failureCode && (
              <div className="p-3 bg-rose-950/40 border border-rose-800 rounded-xl text-xs text-rose-300">
                <div className="flex items-center gap-1.5 font-semibold text-rose-400">
                  <AlertTriangle className="w-4 h-4" />
                  <span>Failure Diagnostic: {inspectMessage.failureCode}</span>
                </div>
                <p className="text-[11px] text-rose-300/80 mt-1">
                  Extraction encountered an error during parsing or attachment inspection.
                </p>
              </div>
            )}

            {/* Attachments List */}
            {inspectMessage.attachments && inspectMessage.attachments.length > 0 && (
              <div className="space-y-2">
                <span className="text-xs font-semibold text-white block">
                  Ingested Attachments ({inspectMessage.attachments.length})
                </span>
                {inspectMessage.attachments.map((att) => (
                  <div
                    key={att.id}
                    className="p-3 bg-slate-950 border border-slate-800 rounded-xl flex items-center justify-between text-xs"
                  >
                    <div className="flex items-center gap-2">
                      <FileText className="w-4 h-4 text-emerald-400" />
                      <div>
                        <span className="font-semibold text-white block">
                          {att.originalFilename}
                        </span>
                        <span className="text-[10px] text-slate-500 font-mono">
                          MIME: {att.detectedMime || att.declaredMime} ({Math.round((att.sizeBytes || 0) / 1024)} KB)
                        </span>
                      </div>
                    </div>
                    <Badge variant="slate" size="sm">
                      {att.status || 'PARSED'}
                    </Badge>
                  </div>
                ))}
              </div>
            )}

            <div className="flex justify-end gap-2 pt-3">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setInspectMessage(null)}
              >
                Close
              </Button>
              <Button
                variant="primary"
                size="sm"
                onClick={() => handleRetry(inspectMessage.id)}
                isLoading={isRetrying}
                icon={<RotateCcw className="w-3.5 h-3.5" />}
              >
                Retry Processing
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
};
