import React, { useState } from 'react';
import { Outlet, Navigate } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Header } from './Header';
import { useAuth } from '../../context/AuthContext';
import { Loader2 } from 'lucide-react';

export const AppLayout: React.FC = () => {
  const { user, isLoading } = useAuth();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  if (isLoading) {
    return (
      <div className="min-h-screen bg-[#F7F8FA] dark:bg-[#0B1220] flex flex-col items-center justify-center text-slate-500 dark:text-slate-400 gap-3">
        <Loader2 className="w-8 h-8 animate-spin text-teal-600 dark:text-teal-400" />
        <span className="text-xs font-medium tracking-wide text-slate-600 dark:text-slate-400">
          Initializing HireFlow AI...
        </span>
      </div>
    );
  }

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="min-h-screen bg-[#F7F8FA] dark:bg-[#0B1220] text-slate-900 dark:text-slate-100 flex">
      {/* Sidebar with Desktop Collapse and Mobile Drawer */}
      <Sidebar
        collapsed={sidebarCollapsed}
        onToggle={() => setSidebarCollapsed(!sidebarCollapsed)}
        mobileOpen={mobileNavOpen}
        onCloseMobile={() => setMobileNavOpen(false)}
      />

      {/* Main Content Area */}
      <div
        data-testid="main-content-wrapper"
        className={`flex-1 flex flex-col min-h-screen transition-all duration-200 ml-0 ${
          sidebarCollapsed ? 'md:ml-20' : 'md:ml-64'
        }`}
      >
        <Header onOpenMobileNav={() => setMobileNavOpen(true)} />
        <main className="w-full max-w-[1536px] mx-auto px-4 sm:px-6 lg:px-8 py-6 flex-1">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
