import React, { useEffect, useState } from 'react';
import {
  Key,
  RotateCw,
  Trash2,
  Copy,
  Check,
  Building,
  Terminal,
  Cpu,
  Sun,
  Moon,
  Sparkles,
} from 'lucide-react';
import { api } from '../services/api';
import { WebhookCredentialDto } from '../types';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../context/ThemeContext';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { useToast } from '../components/common/Toast';

export const SettingsPage: React.FC = () => {
  const { user } = useAuth();
  const { theme, setTheme } = useTheme();
  const { toast } = useToast();

  const [webhookKeys, setWebhookKeys] = useState<WebhookCredentialDto[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  // New Secret Modal
  const [newlyCreatedSecret, setNewlyCreatedSecret] = useState<string | null>(null);
  const [newKeyDescription, setNewKeyDescription] = useState('Production Inbound Forwarder');
  const [isGeneratingKey, setIsGeneratingKey] = useState(false);

  const loadWebhookCredentials = async () => {
    setIsLoading(true);
    try {
      const data = await api.getWebhookCredentials();
      setWebhookKeys(data || []);
    } catch (err: any) {
      toast('Failed to load webhook credentials', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadWebhookCredentials();
  }, []);

  const handleCreateWebhookKey = async () => {
    setIsGeneratingKey(true);
    try {
      const cred = await api.createWebhookCredential(newKeyDescription);
      setNewlyCreatedSecret(cred.secret || 'hf_wh_live_secret_sample_key');
      toast('New Webhook signing credential generated', 'success');
      loadWebhookCredentials();
    } catch (err: any) {
      toast(err.message || 'Failed to create webhook credential', 'error');
    } finally {
      setIsGeneratingKey(false);
    }
  };

  const handleRotateKey = async (id?: string) => {
    if (!id) return;
    if (!confirm('Rotate this webhook key? Existing key will be revoked.')) return;
    try {
      const cred = await api.rotateWebhookCredential(id);
      setNewlyCreatedSecret(cred.secret || 'hf_wh_rotated_secret_key');
      toast('Webhook key rotated successfully', 'success');
      loadWebhookCredentials();
    } catch (err: any) {
      toast(err.message || 'Failed to rotate key', 'error');
    }
  };

  const handleRevokeKey = async (id?: string) => {
    if (!id) return;
    if (!confirm('Revoke this webhook credential permanently?')) return;
    try {
      await api.revokeWebhookCredential(id);
      toast('Webhook credential revoked', 'success');
      loadWebhookCredentials();
    } catch (err: any) {
      toast(err.message || 'Failed to revoke key', 'error');
    }
  };

  return (
    <div className="space-y-8 animate-in fade-in duration-300">
      {/* Top Header */}
      <div>
        <p className="eyebrow">Configuration</p>
        <h1 className="mt-2 text-[26px] font-semibold leading-tight tracking-[-0.03em] text-slate-950 dark:text-slate-50">
          Organization &amp; Security Settings
        </h1>
        <p className="mt-1.5 text-[12.5px] leading-6 text-slate-500 dark:text-slate-400">
          Tenant identity, appearance preferences, HMAC-SHA256 webhook credentials, and runtime diagnostics.
        </p>
      </div>

      {/* Appearance & Theme Selector Section */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-6 shadow-sm">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h3 className="text-base font-bold text-slate-900 dark:text-white flex items-center gap-2">
              <Sparkles className="w-4 h-4 text-teal-600 dark:text-teal-400" />
              Appearance & Theme
            </h3>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              Customize interface theme. Preference is saved across your sessions.
            </p>
          </div>
          <Badge variant="teal" size="sm">
            {theme === 'dark' ? 'Dark Mode Active' : 'Light Mode Active'}
          </Badge>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {/* Light Theme Card */}
          <button
            type="button"
            onClick={() => setTheme('light')}
            className={`p-4 rounded-xl border text-left transition-all flex flex-col justify-between ${
              theme === 'light'
                ? 'border-teal-600 dark:border-teal-400 ring-2 ring-teal-500/20 bg-teal-50/40 dark:bg-teal-950/20'
                : 'border-slate-200 dark:border-slate-800 hover:border-slate-300 dark:hover:border-slate-700 bg-slate-50/60 dark:bg-slate-950/40'
            }`}
            data-testid="theme-light-card"
          >
            <div className="flex items-center justify-between w-full">
              <div className="flex items-center gap-3">
                <div className="p-2.5 rounded-lg bg-white border border-slate-200 text-amber-500 shadow-xs">
                  <Sun className="w-5 h-5" />
                </div>
                <div>
                  <span className="font-semibold text-slate-900 dark:text-white text-xs block">
                    Enterprise Light (Default)
                  </span>
                  <span className="text-[11px] text-slate-500 dark:text-slate-400">
                    Clean, high-contrast daylight palette with soft neutral surfaces
                  </span>
                </div>
              </div>
              {theme === 'light' && (
                <div className="w-5 h-5 rounded-full bg-teal-600 text-white flex items-center justify-center shrink-0">
                  <Check className="w-3.5 h-3.5 stroke-[3]" />
                </div>
              )}
            </div>

            {/* Visual Mini-Preview */}
            <div className="mt-4 pt-3 border-t border-slate-200/70 dark:border-slate-800/80 flex items-center gap-2">
              <span className="w-3 h-3 rounded-full bg-[#F7F4EE] border border-slate-300" />
              <span className="w-3 h-3 rounded-full bg-white border border-slate-300" />
              <span className="w-3 h-3 rounded-full bg-[#24513E]" />
              <span className="text-[10px] text-slate-500 dark:text-slate-400 ml-auto font-mono">
                #F7F4EE / #24513E
              </span>
            </div>
          </button>

          {/* Dark Theme Card */}
          <button
            type="button"
            onClick={() => setTheme('dark')}
            className={`p-4 rounded-xl border text-left transition-all flex flex-col justify-between ${
              theme === 'dark'
                ? 'border-teal-600 dark:border-teal-400 ring-2 ring-teal-500/20 bg-teal-50/40 dark:bg-teal-950/20'
                : 'border-slate-200 dark:border-slate-800 hover:border-slate-300 dark:hover:border-slate-700 bg-slate-50/60 dark:bg-slate-950/40'
            }`}
            data-testid="theme-dark-card"
          >
            <div className="flex items-center justify-between w-full">
              <div className="flex items-center gap-3">
                <div className="p-2.5 rounded-lg bg-slate-900 border border-slate-800 text-teal-400 shadow-xs">
                  <Moon className="w-5 h-5" />
                </div>
                <div>
                  <span className="font-semibold text-slate-900 dark:text-white text-xs block">
                    Obsidian Dark
                  </span>
                  <span className="text-[11px] text-slate-500 dark:text-slate-400">
                    High-legibility deep slate theme optimized for low-light environments
                  </span>
                </div>
              </div>
              {theme === 'dark' && (
                <div className="w-5 h-5 rounded-full bg-teal-600 text-white flex items-center justify-center shrink-0">
                  <Check className="w-3.5 h-3.5 stroke-[3]" />
                </div>
              )}
            </div>

            {/* Visual Mini-Preview */}
            <div className="mt-4 pt-3 border-t border-slate-200/70 dark:border-slate-800/80 flex items-center gap-2">
              <span className="w-3 h-3 rounded-full bg-[#12100E] border border-slate-700" />
              <span className="w-3 h-3 rounded-full bg-[#1A1815] border border-slate-700" />
              <span className="w-3 h-3 rounded-full bg-[#86BB9F]" />
              <span className="text-[10px] text-slate-500 dark:text-slate-400 ml-auto font-mono">
                #12100E / #86BB9F
              </span>
            </div>
          </button>
        </div>
      </div>

      {/* Organization Identity Profile */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-6 shadow-sm">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 rounded-xl bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 flex items-center justify-center text-teal-700 dark:text-teal-400">
            <Building className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-base font-bold text-slate-900 dark:text-white">
              {user?.organizationName || 'Kartgen Infotech LLP'}
            </h3>
            <span className="text-[11px] text-slate-500 dark:text-slate-400 font-mono">
              Tenant ID: {user?.organizationId || 'kartgen'}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 pt-4 border-t border-slate-200 dark:border-slate-800 text-xs">
          <div>
            <span className="text-slate-500 dark:text-slate-400 block text-[11px]">Primary Recruiter</span>
            <span className="font-semibold text-slate-800 dark:text-slate-200">{user?.email || 'hr@kartgen.com'}</span>
          </div>
          <div>
            <span className="text-slate-500 dark:text-slate-400 block text-[11px]">Assigned Role</span>
            <span className="font-semibold text-teal-700 dark:text-teal-400">{user?.role || 'OWNER'}</span>
          </div>
          <div>
            <span className="text-slate-500 dark:text-slate-400 block text-[11px]">Default Timezone</span>
            <span className="font-semibold text-slate-800 dark:text-slate-200">Asia/Kolkata (IST)</span>
          </div>
        </div>
      </div>

      {/* Inbound Webhook Credentials (HMAC-SHA256) */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-6 shadow-sm space-y-5">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h3 className="text-base font-bold text-slate-900 dark:text-white flex items-center gap-2">
              <Key className="w-4 h-4 text-teal-600 dark:text-teal-400" />
              Inbound Webhook Credentials (HMAC-SHA256)
            </h3>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              Secure webhook signature keys used to verify automated mail forwarders.
            </p>
          </div>

          <Button
            variant="primary"
            size="sm"
            onClick={handleCreateWebhookKey}
            isLoading={isGeneratingKey}
            icon={<Key className="w-3.5 h-3.5" />}
          >
            Generate Signing Key
          </Button>
        </div>

        {/* Credentials Table */}
        <div className="border border-slate-200 dark:border-slate-800 rounded-xl overflow-hidden">
          <table className="w-full text-left text-xs">
            <thead className="bg-slate-50 dark:bg-slate-950/80 border-b border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 font-semibold text-[11px]">
              <tr>
                <th className="py-3 px-4">Key ID</th>
                <th className="py-3 px-4">Status</th>
                <th className="py-3 px-4">Created Date</th>
                <th className="py-3 px-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 dark:divide-slate-800/60">
              {isLoading ? (
                <tr>
                  <td colSpan={4} className="py-8 text-center text-slate-500 dark:text-slate-400">
                    Loading credentials...
                  </td>
                </tr>
              ) : webhookKeys.length === 0 ? (
                <tr>
                  <td colSpan={4} className="py-8 text-center text-slate-500 dark:text-slate-400">
                    No active webhook signing keys generated yet.
                  </td>
                </tr>
              ) : (
                webhookKeys.map((key) => (
                  <tr key={key.keyId} className="hover:bg-slate-50 dark:hover:bg-slate-800/30">
                    <td className="py-3 px-4 font-mono text-slate-800 dark:text-slate-300 font-medium">
                      {key.keyId}
                    </td>
                    <td className="py-3 px-4">
                      <Badge variant={key.status === 'ACTIVE' ? 'emerald' : 'rose'} size="sm" dot>
                        {key.status}
                      </Badge>
                    </td>
                    <td className="py-3 px-4 text-slate-500 dark:text-slate-400 font-mono text-[11px]">
                      {new Date(key.createdAt).toLocaleDateString()}
                    </td>
                    <td className="py-3 px-4 text-right space-x-2">
                      {key.status === 'ACTIVE' && (
                        <>
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => handleRotateKey(key.id || key.keyId)}
                            icon={<RotateCw className="w-3 h-3" />}
                          >
                            Rotate
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleRevokeKey(key.id || key.keyId)}
                            className="text-slate-400 hover:text-rose-600 dark:text-slate-500 dark:hover:text-rose-400"
                            aria-label={`Revoke ${key.keyId}`}
                          >
                            <Trash2 className="w-3 h-3" />
                          </Button>
                        </>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Integration Code Snippet */}
        <div className="bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl p-4">
          <div className="flex items-center justify-between text-xs text-slate-600 dark:text-slate-400 mb-2">
            <span className="font-semibold text-slate-800 dark:text-slate-300 flex items-center gap-1.5">
              <Terminal className="w-3.5 h-3.5 text-teal-600 dark:text-teal-400" />
              Webhook Verification Header Signature (`X-HireFlow-Signature`)
            </span>
          </div>
          <pre className="text-[11px] font-mono text-slate-800 dark:text-slate-300 overflow-x-auto whitespace-pre leading-relaxed p-3 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-lg">
{`# Python Inbound Webhook Signing Example:
import hmac, hashlib
signature = hmac.new(WEBHOOK_SECRET.encode('utf-8'), request_body, hashlib.sha256).hexdigest()
headers = {
    'X-HireFlow-Key-Id': KEY_ID,
    'X-HireFlow-Signature': signature,
    'Content-Type': 'application/json'
}`}
          </pre>
        </div>
      </div>

      {/* System Diagnostics */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-6 shadow-sm">
        <h3 className="text-base font-bold text-slate-900 dark:text-white mb-4 flex items-center gap-2">
          <Cpu className="w-4 h-4 text-teal-600 dark:text-teal-400" />
          Runtime Environment & Security Diagnostics
        </h3>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-xs">
          <div className="p-3 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl">
            <span className="text-slate-500 dark:text-slate-400 block text-[11px]">Runtime JDK</span>
            <span className="font-semibold text-slate-800 dark:text-slate-200 font-mono">Java 21 (LTS)</span>
          </div>
          <div className="p-3 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl">
            <span className="text-slate-500 dark:text-slate-400 block text-[11px]">Spring Boot</span>
            <span className="font-semibold text-slate-800 dark:text-slate-200 font-mono">3.3.5</span>
          </div>
          <div className="p-3 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl">
            <span className="text-slate-500 dark:text-slate-400 block text-[11px]">Apache Tika</span>
            <span className="font-semibold text-teal-700 dark:text-teal-400 font-mono">3.2.2 (Patched)</span>
          </div>
          <div className="p-3 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl">
            <span className="text-slate-500 dark:text-slate-400 block text-[11px]">Database</span>
            <span className="font-semibold text-slate-800 dark:text-slate-200 font-mono">PostgreSQL 16 / H2</span>
          </div>
        </div>
      </div>

      {/* Secret Display Modal */}
      {newlyCreatedSecret && (
        <Modal
          isOpen={true}
          onClose={() => setNewlyCreatedSecret(null)}
          title="Save Webhook Secret Key"
          subtitle="This secret will only be shown ONCE for security. Save it securely."
        >
          <div className="space-y-4">
            <div className="p-3.5 bg-amber-50 dark:bg-amber-950/40 border border-amber-200 dark:border-amber-800 rounded-xl text-xs text-amber-800 dark:text-amber-300">
              Please copy this secret immediately. It is encrypted on the server and cannot be retrieved again.
            </div>

            <div className="p-3 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl flex items-center justify-between">
              <span className="font-mono text-teal-700 dark:text-teal-400 text-xs select-all break-all font-semibold">
                {newlyCreatedSecret}
              </span>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  navigator.clipboard.writeText(newlyCreatedSecret);
                  toast('Secret copied to clipboard', 'success');
                }}
                aria-label="Copy secret"
              >
                <Copy className="w-4 h-4" />
              </Button>
            </div>

            <div className="flex justify-end pt-2">
              <Button
                variant="primary"
                size="sm"
                onClick={() => setNewlyCreatedSecret(null)}
              >
                I Have Stored the Secret
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
};
