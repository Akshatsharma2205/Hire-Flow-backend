import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { api, setAuthToken, setOnUnauthorizedCallback } from '../services/api';
import { UserSession } from '../types';

interface AuthContextType {
  user: UserSession | null;
  token: string | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  quickDemoLogin: () => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<UserSession | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  const logout = () => {
    setUser(null);
    setToken(null);
    setAuthToken(null);
  };

  useEffect(() => {
    setOnUnauthorizedCallback(logout);
    // Attempt auto-login with default demo credentials for instantaneous startup
    const bootstrap = async () => {
      try {
        const authData = await api.login({
          email: 'hr@kartgen.com',
          password: 'password123',
        });
        setToken(authData.token);
        setUser({
          ...authData,
          displayName: authData.displayName || 'Kartgen HR Admin',
          organizationName: authData.organizationName || 'Kartgen Infotech LLP',
          authenticated: true,
        });
      } catch (err) {
        console.warn('Initial auto-login unavailable, presenting login page:', err);
      } finally {
        setIsLoading(false);
      }
    };
    bootstrap();
  }, []);

  const login = async (email: string, password: string) => {
    setIsLoading(true);
    try {
      const authData = await api.login({ email, password });
      setToken(authData.token);
      setUser({
        ...authData,
        displayName: authData.displayName || (email.split('@')[0].toUpperCase()),
        organizationName: authData.organizationName || 'Kartgen Infotech LLP',
        authenticated: true,
      });
    } finally {
      setIsLoading(false);
    }
  };

  const quickDemoLogin = async () => {
    return login('hr@kartgen.com', 'password123');
  };

  return (
    <AuthContext.Provider value={{ user, token, isLoading, login, quickDemoLogin, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
