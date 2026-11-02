import React, { useEffect, useState } from 'react';
import {
  Settings,
  Key,
  Shield,
  RotateCw,
  Trash2,
  Copy,
  Check,
  Building,
  Terminal,
  Cpu,
  Layers,
  Lock,
} from 'lucide-react';
import { api } from '../services/api';
import { WebhookCredentialDto } from '../types';
import { useAuth } from '../context/AuthContext';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { useToast } from '../components/common/Toast';

export const SettingsPage: React.FC = () => {
  const { user } = useAuth();
  const { toast } = useToast();

  const [webhookKeys, setWebhookKeys] = useState<WebhookCredentialDto[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  // New Secret Modal
  const [newlyCreatedSecret, setNewlyCreatedSecret] = useState<string | null>(null);
  const [newKeyDescription, setNewKeyDescription] = useState('Production Inbound Forwarder');
  const [isGeneratingKey, setIsGeneratingKey] = useState(false);

  // Extension token
  const [extensionToken, setExtensionToken] = useState<string | null>(null);

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
        <h1 className="text-2xl font-bold tracking-tight text-white">Organization & Security Settings</h1>
        <p className="text-xs text-slate-400 mt-1">
          Tenant identity, HMAC-SHA256 webhook credentials, and system diagnostics.
        </p>
      </div>

      {/* Organization Identity Profile */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 rounded-xl bg-slate-800 border border-slate-700 flex items-center justify-center text-emerald-400">
            <Building className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-base font-bold text-white">
              {user?.organizationName || 'Kartgen Infotech LLP'}
            </h3>
            <span className="text-[11px] text-slate-400 font-mono">
              Tenant ID: {user?.organizationId || 'kartgen'}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 pt-4 border-t border-slate-800 text-xs">
          <div>
            <span className="text-slate-500 block text-[11px]">Primary Recruiter</span>
            <span className="font-semibold text-slate-200">{user?.email}</span>
          </div>
          <div>
            <span className="text-slate-500 block text-[11px]">Assigned Role</span>
            <span className="font-semibold text-emerald-400">{user?.role || 'OWNER'}</span>
          </div>
          <div>
            <span className="text-slate-500 block text-[11px]">Default Timezone</span>
            <span className="font-semibold text-slate-200">Asia/Kolkata (IST)</span>
          </div>
        </div>
      </div>

      {/* Inbound Webhook Credentials (HMAC-SHA256) */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl space-y-5">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h3 className="text-base font-bold text-white flex items-center gap-2">
              <Key className="w-4 h-4 text-emerald-400" />
              Inbound Webhook Credentials (HMAC-SHA256)
            </h3>
            <p className="text-xs text-slate-400 mt-0.5">
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
        <div className="border border-slate-800 rounded-xl overflow-hidden">
          <table className="w-full text-left text-xs">
            <thead className="bg-slate-950/80 border-b border-slate-800 text-slate-400 font-semibold text-[11px]">
              <tr>
                <th className="py-3 px-4">Key ID</th>
                <th className="py-3 px-4">Status</th>
                <th className="py-3 px-4">Created Date</th>
                <th className="py-3 px-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/60">
              {isLoading ? (
                <tr>
                  <td colSpan={4} className="py-8 text-center text-slate-500">
                    Loading credentials...
                  </td>
                </tr>
              ) : webhookKeys.length === 0 ? (
                <tr>
                  <td colSpan={4} className="py-8 text-center text-slate-500">
                    No active webhook signing keys generated yet.
                  </td>
                </tr>
              ) : (
                webhookKeys.map((key) => (
                  <tr key={key.keyId} className="hover:bg-slate-800/30">
                    <td className="py-3 px-4 font-mono text-slate-300 font-medium">
                      {key.keyId}
                    </td>
                    <td className="py-3 px-4">
                      <Badge variant={key.status === 'ACTIVE' ? 'emerald' : 'rose'} size="sm" dot>
                        {key.status}
                      </Badge>
                    </td>
                    <td className="py-3 px-4 text-slate-400 font-mono text-[11px]">
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
                            className="text-slate-500 hover:text-rose-400"
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
        <div className="bg-slate-950 border border-slate-800 rounded-xl p-4">
          <div className="flex items-center justify-between text-xs text-slate-400 mb-2">
            <span className="font-semibold text-slate-300 flex items-center gap-1.5">
              <Terminal className="w-3.5 h-3.5 text-cyan-400" />
              Webhook Verification Header Signature (`X-HireFlow-Signature`)
            </span>
          </div>
          <pre className="text-[11px] font-mono text-slate-300 overflow-x-auto whitespace-pre leading-relaxed p-2 bg-slate-900 rounded-lg">
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
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl">
        <h3 className="text-base font-bold text-white mb-4 flex items-center gap-2">
          <Cpu className="w-4 h-4 text-emerald-400" />
          Runtime Environment & Security Diagnostics
        </h3>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-xs">
          <div className="p-3 bg-slate-950 border border-slate-800 rounded-xl">
            <span className="text-slate-500 block text-[11px]">Runtime JDK</span>
            <span className="font-semibold text-slate-200 font-mono">Java 21 (LTS)</span>
          </div>
          <div className="p-3 bg-slate-950 border border-slate-800 rounded-xl">
            <span className="text-slate-500 block text-[11px]">Spring Boot</span>
            <span className="font-semibold text-slate-200 font-mono">3.3.5</span>
          </div>
          <div className="p-3 bg-slate-950 border border-slate-800 rounded-xl">
            <span className="text-slate-500 block text-[11px]">Apache Tika</span>
            <span className="font-semibold text-emerald-400 font-mono">3.2.2 (Patched)</span>
          </div>
          <div className="p-3 bg-slate-950 border border-slate-800 rounded-xl">
            <span className="text-slate-500 block text-[11px]">Database</span>
            <span className="font-semibold text-slate-200 font-mono">PostgreSQL 16 / H2</span>
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
            <div className="p-3.5 bg-amber-950/40 border border-amber-800 rounded-xl text-xs text-amber-300">
              Please copy this secret immediately. It is encrypted on the server and cannot be retrieved again.
            </div>

            <div className="p-3 bg-slate-950 border border-slate-800 rounded-xl flex items-center justify-between">
              <span className="font-mono text-emerald-400 text-xs select-all break-all">
                {newlyCreatedSecret}
              </span>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  navigator.clipboard.writeText(newlyCreatedSecret);
                  toast('Secret copied to clipboard', 'success');
                }}
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
