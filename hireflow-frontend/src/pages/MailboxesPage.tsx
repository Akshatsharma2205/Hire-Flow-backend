import React, { useEffect, useState } from 'react';
import {
  Mail,
  RefreshCw,
  Trash2,
  Key,
  RotateCcw,
  ChevronDown,
  ChevronUp,
  Copy,
  Send,
  Sparkles,
} from 'lucide-react';
import { api } from '../services/api';
import { MailConnectionDto } from '../types';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { useToast } from '../components/common/Toast';

export const MailboxesPage: React.FC = () => {
  const { toast } = useToast();

  const [connections, setConnections] = useState<MailConnectionDto[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [syncingId, setSyncingId] = useState<string | null>(null);

  // IMAP Form state
  const [showImapForm, setShowImapForm] = useState(false);
  const [imapHost, setImapHost] = useState('imap.example.com');
  const [imapPort, setImapPort] = useState(993);
  const [imapUsername, setImapUsername] = useState('');
  const [imapPassword, setImapPassword] = useState('');
  const [imapFolder, setImapFolder] = useState('INBOX');
  const [isSubmittingImap, setIsSubmittingImap] = useState(false);

  // Chrome Extension section state
  const [extensionToken, setExtensionToken] = useState<string | null>(null);
  const [isGeneratingToken, setIsGeneratingToken] = useState(false);
  const [testHashInput, setTestHashInput] = useState('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  const [hashResult, setHashResult] = useState<string | null>(null);
  const [testExtensionFile, setTestExtensionFile] = useState<File | null>(null);
  const [isTestingExtension, setIsTestingExtension] = useState(false);

  const loadConnections = async () => {
    setIsLoading(true);
    try {
      const data = await api.getMailConnections();
      setConnections(data || []);
    } catch (err: any) {
      toast('Failed to load mailbox connections', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    // 1. Process OAuth Redirect Result (popup blocker fallback)
    const searchParams = new URLSearchParams(window.location.search);
    if (searchParams.has('oauth')) {
      const status = searchParams.get('oauth');
      const provider = searchParams.get('provider') || '';
      const mailbox = searchParams.get('mailbox') || '';
      const errorMsg = searchParams.get('msg') || searchParams.get('error') || 'Mailbox authorization was not completed';

      if (status === 'success') {
        const provLabel = provider.toLowerCase().includes('micro')
          ? 'Microsoft 365'
          : 'Google Workspace';
        const mailboxStr = mailbox ? ` (${mailbox})` : '';
        toast(`Successfully connected ${provLabel}${mailboxStr}!`, 'success');
      } else if (status === 'error') {
        toast(`Connection failed: ${errorMsg}`, 'error');
      }

      // Sanitize URL address bar and browser history
      const cleanUrl = window.location.pathname + window.location.hash;
      window.history.replaceState({}, document.title, cleanUrl);
    }

    loadConnections();

    // Popup PostMessage Listener for OAuth Completion
    const handleOAuthMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      if (!event.data || event.data.type !== 'HIREFLOW_OAUTH_CALLBACK') return;

      if (event.data.status === 'SUCCESS') {
        const provLabel = (event.data.provider || '').toLowerCase().includes('micro')
          ? 'Microsoft 365'
          : 'Google Workspace';
        const mailbox = event.data.mailboxAddress ? ` (${event.data.mailboxAddress})` : '';
        toast(`Successfully connected ${provLabel}${mailbox}!`, 'success');
        loadConnections();
      } else {
        const errorMsg = event.data.error || 'Mailbox authorization was not completed';
        toast(`Connection failed: ${errorMsg}`, 'error');
        loadConnections();
      }
    };

    window.addEventListener('message', handleOAuthMessage);
    return () => window.removeEventListener('message', handleOAuthMessage);
  }, []);

  const handleOAuthConnect = async (provider: 'google' | 'microsoft') => {
    try {
      const res = await api.getOAuthAuthorizeUrl(provider);
      const width = 600;
      const height = 700;
      const left = window.screenX + Math.max(0, (window.outerWidth - width) / 2);
      const top = window.screenY + Math.max(0, (window.outerHeight - height) / 2);

      const popup = window.open(
        res.authorizationUrl,
        'hireflow_oauth_popup',
        `width=${width},height=${height},left=${left},top=${top},scrollbars=yes,status=no,resizable=yes`
      );

      if (!popup || popup.closed || typeof popup.closed === 'undefined') {
        // Fallback if popup blocked
        window.location.href = res.authorizationUrl;
      } else {
        popup.focus();
      }
    } catch (err: any) {
      toast(err.message || `Failed to initiate ${provider} OAuth flow`, 'error');
    }
  };

  const handleSyncNow = async (id: string) => {
    setSyncingId(id);
    try {
      await api.syncNow(id);
      toast('Incremental mailbox sync triggered successfully', 'success');
      loadConnections();
    } catch (err: any) {
      toast(err.message || 'Sync failed', 'error');
    } finally {
      setSyncingId(null);
    }
  };

  const handleDisconnect = async (id: string, email: string) => {
    if (!confirm(`Are you sure you want to disconnect inbox ${email}?`)) return;
    try {
      await api.disconnectMailbox(id);
      toast(`Disconnected ${email}`, 'success');
      loadConnections();
    } catch (err: any) {
      toast(err.message || 'Failed to disconnect mailbox', 'error');
    }
  };

  const handleCreateImap = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!imapHost || !imapUsername || !imapPassword) {
      toast('Host, Username, and Password are required', 'error');
      return;
    }
    setIsSubmittingImap(true);
    try {
      await api.createImapConnection({
        host: imapHost.trim(),
        port: Number(imapPort),
        username: imapUsername.trim(),
        password: imapPassword,
        folderFilter: imapFolder.trim() || 'INBOX',
        ssl: true,
      });
      toast(`Connected IMAP account: ${imapUsername}`, 'success');
      setShowImapForm(false);
      setImapUsername('');
      setImapPassword('');
      loadConnections();
    } catch (err: any) {
      toast(err.message || 'Failed to establish IMAP connection', 'error');
    } finally {
      setIsSubmittingImap(false);
    }
  };

  const handleGenerateExtensionToken = async () => {
    setIsGeneratingToken(true);
    try {
      const res = await api.getExtensionToken();
      setExtensionToken(res.extensionToken);
      toast('New Chrome Extension token generated', 'success');
    } catch (err: any) {
      toast('Failed to generate extension token', 'error');
    } finally {
      setIsGeneratingToken(false);
    }
  };

  const handleCheckHashes = async () => {
    if (!testHashInput.trim()) return;
    try {
      const hashes = testHashInput.split(',').map((h) => h.trim());
      const res = await api.checkHashes(hashes);
      setHashResult(
        res.existingHashes && res.existingHashes.length > 0
          ? `Matches found: ${res.existingHashes.join(', ')}`
          : 'Zero duplicates found in organization storage. Ready for intake.'
      );
    } catch (err: any) {
      toast('Hash check failed', 'error');
    }
  };

  const handleTestExtensionIntake = async () => {
    if (!testExtensionFile) {
      toast('Select a resume file to test extension dispatch', 'error');
      return;
    }
    setIsTestingExtension(true);
    try {
      await api.extensionIntake(testExtensionFile, {
        source: 'CHROME_EXTENSION_SIMULATOR',
        candidateName: 'Extension Test Applicant',
      });
      toast(`Extension intake successfully ingested ${testExtensionFile.name}!`, 'success');
      setTestExtensionFile(null);
    } catch (err: any) {
      toast(err.message || 'Extension intake simulation failed', 'error');
    } finally {
      setIsTestingExtension(false);
    }
  };

  return (
    <div className="space-y-8 animate-in fade-in duration-300">
      {/* Top Header */}
      <div>
        <p className="eyebrow">Integrations</p>
        <h1 className="mt-2 text-[26px] font-semibold leading-tight tracking-[-0.03em] text-slate-950 dark:text-slate-50">
          Mailbox Integration &amp; Intake
        </h1>
        <p className="mt-1.5 text-[12.5px] leading-6 text-slate-500 dark:text-slate-400">
          Connect corporate recruitment inboxes via OAuth 2.0 PKCE or manage browser extension tokens.
        </p>
      </div>

      {/* Cloud OAuth Provider Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
        {/* Google Workspace */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-6 flex flex-col justify-between shadow-sm card-hover transition-all">
          <div>
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-slate-50 dark:bg-white border border-slate-200 dark:border-transparent flex items-center justify-center p-2 shrink-0">
                <svg viewBox="0 0 24 24" className="w-full h-full">
                  <path
                    fill="#4285F4"
                    d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"
                  />
                  <path
                    fill="#34A853"
                    d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
                  />
                  <path
                    fill="#FBBC05"
                    d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"
                  />
                  <path
                    fill="#EA4335"
                    d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"
                  />
                </svg>
              </div>
              <div>
                <h3 className="text-base font-bold text-slate-900 dark:text-white">Google Workspace</h3>
                <span className="text-[11px] text-slate-500 dark:text-slate-400">
                  Gmail API with incremental history ID sync
                </span>
              </div>
            </div>
            <p className="text-xs text-slate-600 dark:text-slate-400 mt-4 leading-relaxed">
              Synchronize recruitment inboxes (e.g. <span className="font-mono text-slate-800 dark:text-slate-200">careers@yourdomain.com</span>). Automated token rotation and AES-256-GCM encrypted persistence.
            </p>
          </div>

          <div className="mt-6 pt-4 border-t border-slate-200 dark:border-slate-800">
            <Button
              variant="primary"
              size="sm"
              className="w-full"
              onClick={() => handleOAuthConnect('google')}
            >
              Connect Google Workspace Inbox
            </Button>
          </div>
        </div>

        {/* Microsoft 365 */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-6 flex flex-col justify-between shadow-sm card-hover transition-all">
          <div>
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 flex items-center justify-center p-2 shrink-0">
                <svg viewBox="0 0 24 24" className="w-6 h-6">
                  <path fill="#f25022" d="M1 1h10v10H1z" />
                  <path fill="#7fba00" d="M13 1h10v10H13z" />
                  <path fill="#00a4ef" d="M1 13h10v10H1z" />
                  <path fill="#ffb900" d="M13 13h10v10H13z" />
                </svg>
              </div>
              <div>
                <h3 className="text-base font-bold text-slate-900 dark:text-white">Microsoft 365</h3>
                <span className="text-[11px] text-slate-500 dark:text-slate-400">
                  Microsoft Graph API with delta tokens
                </span>
              </div>
            </div>
            <p className="text-xs text-slate-600 dark:text-slate-400 mt-4 leading-relaxed">
              Connect Exchange Online / Outlook mailboxes. Supports shared folders, delta token paging, and automatic HTTP 429 backoff handling.
            </p>
          </div>

          <div className="mt-6 pt-4 border-t border-slate-200 dark:border-slate-800">
            <Button
              variant="secondary"
              size="sm"
              className="w-full text-slate-800 dark:text-white"
              onClick={() => handleOAuthConnect('microsoft')}
            >
              Connect Microsoft 365 Inbox
            </Button>
          </div>
        </div>
      </div>

      {/* Active Mailbox Connections List */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl overflow-hidden shadow-sm">
        <div className="p-5 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Mail className="w-4 h-4 text-teal-600 dark:text-teal-400" />
            <h2 className="text-sm font-semibold text-slate-900 dark:text-white">
              Active Mailbox Connections
            </h2>
          </div>
          <Badge variant="slate" size="sm">
            {connections.length} Connected
          </Badge>
        </div>

        <div className="divide-y divide-slate-200 dark:divide-slate-800/60">
          {isLoading ? (
            <div className="py-12 text-center text-slate-500 dark:text-slate-400 text-xs">
              Loading mailbox integrations...
            </div>
          ) : connections.length === 0 ? (
            <div className="py-12 text-center text-slate-500 dark:text-slate-400 text-xs">
              No mailboxes connected yet. Click Google Workspace or Microsoft 365 above.
            </div>
          ) : (
            connections.map((conn) => {
              const isReauth = conn.status === 'REAUTH';
              return (
                <div
                  key={conn.id}
                  className="p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-4 hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 flex items-center justify-center text-teal-700 dark:text-teal-400 shrink-0 font-bold text-xs">
                      {conn.provider === 'GOOGLE' ? 'G' : conn.provider === 'MICROSOFT' ? 'M' : 'I'}
                    </div>
                    <div>
                      <span className="font-semibold text-slate-900 dark:text-white text-xs block">
                        {conn.emailAddress}
                      </span>
                      <div className="flex items-center gap-2 mt-0.5 text-[11px] text-slate-500 dark:text-slate-400">
                        <span>
                          Folder: <strong className="text-slate-700 dark:text-slate-300">{conn.folderFilter || 'INBOX'}</strong>
                        </span>
                        <span>•</span>
                        <span>
                          Last synced:{' '}
                          {conn.lastSyncAt ? new Date(conn.lastSyncAt).toLocaleTimeString() : 'Never'}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-2 self-end sm:self-center">
                    <Badge
                      variant={conn.status === 'ACTIVE' ? 'emerald' : isReauth ? 'amber' : 'rose'}
                      size="sm"
                      dot
                    >
                      {conn.status}
                    </Badge>

                    {/* Inline Reauthorize Action for REAUTH Status */}
                    {isReauth && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() =>
                          handleOAuthConnect((conn.provider || 'google').toLowerCase() as 'google' | 'microsoft')
                        }
                        className="border-amber-300 dark:border-amber-700 text-amber-700 dark:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-950/40"
                        icon={<RotateCcw className="w-3.5 h-3.5" />}
                      >
                        Reauthorize
                      </Button>
                    )}

                    <Button
                      variant="outline"
                      size="sm"
                      isLoading={syncingId === conn.id}
                      onClick={() => handleSyncNow(conn.id)}
                      icon={<RefreshCw className="w-3.5 h-3.5" />}
                    >
                      Sync Now
                    </Button>

                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleDisconnect(conn.id, conn.emailAddress)}
                      className="text-slate-400 hover:text-rose-600 dark:text-slate-500 dark:hover:text-rose-400"
                      aria-label={`Disconnect ${conn.emailAddress}`}
                    >
                      <Trash2 className="w-4 h-4" />
                    </Button>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>

      {/* Custom IMAP / On-Premise Form Accordion */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl overflow-hidden shadow-sm">
        <button
          onClick={() => setShowImapForm(!showImapForm)}
          className="w-full p-4 flex items-center justify-between text-left hover:bg-slate-50 dark:hover:bg-slate-800/40 transition-colors"
        >
          <div className="flex items-center gap-2">
            <Key className="w-4 h-4 text-slate-500 dark:text-slate-400" />
            <span className="text-xs font-semibold text-slate-800 dark:text-slate-200">
              Custom IMAP / On-Premise Mail Server Setup
            </span>
          </div>
          {showImapForm ? (
            <ChevronUp className="w-4 h-4 text-slate-500 dark:text-slate-400" />
          ) : (
            <ChevronDown className="w-4 h-4 text-slate-500 dark:text-slate-400" />
          )}
        </button>

        {showImapForm && (
          <form
            onSubmit={handleCreateImap}
            className="p-5 border-t border-slate-200 dark:border-slate-800 space-y-4 bg-slate-50/60 dark:bg-slate-950/40"
          >
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div>
                <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
                  IMAP Host
                </label>
                <input
                  type="text"
                  value={imapHost}
                  onChange={(e) => setImapHost(e.target.value)}
                  className="w-full px-3 py-1.5 bg-white dark:bg-slate-950 border border-slate-300 dark:border-slate-800 rounded-lg text-xs text-slate-900 dark:text-white focus:outline-none focus:border-teal-500 dark:focus:border-teal-400"
                  required
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
                  Port (SSL)
                </label>
                <input
                  type="number"
                  value={imapPort}
                  onChange={(e) => setImapPort(Number(e.target.value))}
                  className="w-full px-3 py-1.5 bg-white dark:bg-slate-950 border border-slate-300 dark:border-slate-800 rounded-lg text-xs text-slate-900 dark:text-white focus:outline-none focus:border-teal-500 dark:focus:border-teal-400"
                  required
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
                  Folder Filter
                </label>
                <input
                  type="text"
                  value={imapFolder}
                  onChange={(e) => setImapFolder(e.target.value)}
                  className="w-full px-3 py-1.5 bg-white dark:bg-slate-950 border border-slate-300 dark:border-slate-800 rounded-lg text-xs text-slate-900 dark:text-white focus:outline-none focus:border-teal-500 dark:focus:border-teal-400"
                />
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
                  Username / Email
                </label>
                <input
                  type="text"
                  value={imapUsername}
                  onChange={(e) => setImapUsername(e.target.value)}
                  placeholder="careers@internal.company"
                  className="w-full px-3 py-1.5 bg-white dark:bg-slate-950 border border-slate-300 dark:border-slate-800 rounded-lg text-xs text-slate-900 dark:text-white focus:outline-none focus:border-teal-500 dark:focus:border-teal-400"
                  required
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
                  App Password
                </label>
                <input
                  type="password"
                  value={imapPassword}
                  onChange={(e) => setImapPassword(e.target.value)}
                  placeholder="••••••••••••"
                  className="w-full px-3 py-1.5 bg-white dark:bg-slate-950 border border-slate-300 dark:border-slate-800 rounded-lg text-xs text-slate-900 dark:text-white focus:outline-none focus:border-teal-500 dark:focus:border-teal-400"
                  required
                />
              </div>
            </div>

            <div className="flex justify-end">
              <Button type="submit" variant="primary" size="sm" isLoading={isSubmittingImap}>
                Save IMAP Integration
              </Button>
            </div>
          </form>
        )}
      </div>

      {/* Chrome Extension Intake Hub */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-6 shadow-sm">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h3 className="text-base font-bold text-slate-900 dark:text-white flex items-center gap-2">
              <Sparkles className="w-4 h-4 text-teal-600 dark:text-teal-400" />
              Chrome Extension File Intake Hub
            </h3>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              One-click ingestion from LinkedIn, Job Boards, or local files via the HireFlow Extension.
            </p>
          </div>

          <Button
            variant="outline"
            size="sm"
            onClick={handleGenerateExtensionToken}
            isLoading={isGeneratingToken}
            icon={<Key className="w-3.5 h-3.5" />}
          >
            Generate Token
          </Button>
        </div>

        {extensionToken && (
          <div className="p-3 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl mb-4 flex items-center justify-between">
            <div>
              <span className="text-[10px] text-slate-500 dark:text-slate-400 uppercase tracking-wider block font-medium">
                Extension Bearer Token
              </span>
              <span className="text-xs font-mono text-teal-700 dark:text-teal-400 select-all font-semibold">
                {extensionToken}
              </span>
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                navigator.clipboard.writeText(extensionToken);
                toast('Token copied to clipboard', 'success');
              }}
              aria-label="Copy token"
            >
              <Copy className="w-3.5 h-3.5" />
            </Button>
          </div>
        )}

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5 mt-4">
          {/* SHA-256 Pre-check Tester */}
          <div className="p-4 bg-slate-50/70 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 rounded-xl text-xs space-y-3">
            <span className="font-semibold text-slate-800 dark:text-slate-200 block">
              1. SHA-256 Duplicate Pre-Check Tester
            </span>
            <input
              type="text"
              value={testHashInput}
              onChange={(e) => setTestHashInput(e.target.value)}
              className="w-full px-3 py-1.5 bg-white dark:bg-slate-950 border border-slate-300 dark:border-slate-800 rounded-lg text-xs font-mono text-slate-800 dark:text-slate-300 focus:outline-none focus:border-teal-500 dark:focus:border-teal-400"
            />
            <Button variant="secondary" size="sm" onClick={handleCheckHashes}>
              Verify Hash In Repertoire
            </Button>
            {hashResult && (
              <div className="p-2.5 rounded-lg bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-[11px] text-slate-700 dark:text-slate-300">
                {hashResult}
              </div>
            )}
          </div>

          {/* Multipart Extension File Intake Tester */}
          <div className="p-4 bg-slate-50/70 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 rounded-xl text-xs space-y-3">
            <span className="font-semibold text-slate-800 dark:text-slate-200 block">
              2. Extension Dispatch Simulator
            </span>
            <input
              type="file"
              accept=".pdf,.docx"
              onChange={(e) => setTestExtensionFile(e.target.files?.[0] || null)}
              className="block w-full text-xs text-slate-500 dark:text-slate-400 file:mr-3 file:py-1.5 file:px-3 file:rounded-lg file:border-0 file:bg-slate-200 dark:file:bg-slate-800 file:text-slate-700 dark:file:text-slate-300"
            />
            <Button
              variant="primary"
              size="sm"
              disabled={!testExtensionFile}
              isLoading={isTestingExtension}
              onClick={handleTestExtensionIntake}
              icon={<Send className="w-3.5 h-3.5" />}
            >
              Dispatch Extension Intake Payload
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};
