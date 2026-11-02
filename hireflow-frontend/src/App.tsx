import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { ThemeProvider } from './context/ThemeContext';
import { AuthProvider } from './context/AuthContext';
import { ToastProvider } from './components/common/Toast';
import { AppLayout } from './components/layout/AppLayout';
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { CandidatesPage } from './pages/CandidatesPage';
import { JobsPage } from './pages/JobsPage';
import { ApplicationsPage } from './pages/ApplicationsPage';
import { MailboxesPage } from './pages/MailboxesPage';
import { RulesPage } from './pages/RulesPage';
import { ActivityPage } from './pages/ActivityPage';
import { SettingsPage } from './pages/SettingsPage';
import { DocsPage } from './pages/DocsPage';

const IndexHtmlRedirect: React.FC = () => {
  const location = useLocation();
  return <Navigate to={{ pathname: '/mailboxes', search: location.search }} replace />;
};

export const App: React.FC = () => {
  return (
    <BrowserRouter>
      <ThemeProvider>
        <AuthProvider>
          <ToastProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />

            <Route path="/" element={<AppLayout />}>
              <Route index element={<Navigate to="/dashboard" replace />} />
              <Route path="index.html" element={<IndexHtmlRedirect />} />
              <Route path="dashboard" element={<DashboardPage />} />
              <Route path="candidates" element={<CandidatesPage />} />
              <Route path="jobs" element={<JobsPage />} />
              <Route path="applications" element={<ApplicationsPage />} />
              <Route path="mailboxes" element={<MailboxesPage />} />
              <Route path="rules" element={<RulesPage />} />
              <Route path="activity" element={<ActivityPage />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="docs" element={<DocsPage />} />
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Route>
          </Routes>
        </ToastProvider>
      </AuthProvider>
    </ThemeProvider>
  </BrowserRouter>
  );
};

export default App;
