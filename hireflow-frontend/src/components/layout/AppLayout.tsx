import React, { useState } from 'react';
import { Outlet, Navigate } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Header } from './Header';
import { useAuth } from '../../context/AuthContext';
import { BrandMark } from '../brand/BrandMark';

export const AppLayout: React.FC = () => {
  const { user, isLoading } = useAuth();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  if (isLoading) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-[#F7F4EE] text-slate-500 dark:bg-[#12100E] dark:text-slate-400">
        <BrandMark size="lg" className="animate-soft-pulse" />
        <div className="flex flex-col items-center gap-1.5">
          <span className="text-[12px] font-semibold tracking-[-0.01em] text-slate-700 dark:text-slate-200">
            Opening your workspace
          </span>
          <span className="text-[10.5px] uppercase tracking-[0.16em] text-slate-400 dark:text-slate-500">
            Verifying session
          </span>
        </div>
      </div>
    );
  }

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="flex min-h-screen bg-[#F7F4EE] text-slate-900 dark:bg-[#12100E] dark:text-slate-100">
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
        className={`ml-0 flex min-h-screen min-w-0 flex-1 flex-col transition-[margin] duration-300 ease-out-expo ${
          sidebarCollapsed ? 'md:ml-20' : 'md:ml-64'
        }`}
      >
        <Header onOpenMobileNav={() => setMobileNavOpen(true)} />
        <main className="mx-auto w-full max-w-[1536px] flex-1 px-4 py-6 sm:px-6 sm:py-8 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
