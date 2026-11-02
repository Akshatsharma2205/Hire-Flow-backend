import archDoc from '../../../docs/ARCHITECTURE.md?raw';
import apiDoc from '../../../docs/API_REFERENCE.md?raw';
import dbDoc from '../../../docs/DATABASE.md?raw';
import opsDoc from '../../../docs/OPERATIONS.md?raw';
import deployDoc from '../../../docs/DEPLOYMENT.md?raw';
import secDoc from '../../../docs/SECURITY.md?raw';
import testDoc from '../../../docs/TESTING.md?raw';
import extDoc from '../../../docs/EXTENSION.md?raw';
import whDoc from '../../../docs/webhook-security.md?raw';
import upgradeDoc from '../../../docs/UPGRADE_EVALUATION.md?raw';
import agentGuideDoc from '../../../docs/AGENT_GUIDE.md?raw';
import readmeDoc from '../../../README.md?raw';
import agentsDoc from '../../../AGENTS.md?raw';
import projectDoc from '../../../PROJECT.md?raw';
import repoDoc from '../../../REPOSITORY_STRUCTURE.md?raw';
import testInfraDoc from '../../../TEST_INFRA.md?raw';
import testReadyDoc from '../../../TEST_READY.md?raw';

export interface DocArticle {
  id: string;
  title: string;
  category: string;
  description: string;
  content: string;
}

export interface DocCategory {
  name: string;
  articles: DocArticle[];
}

export const DOC_CATEGORIES: DocCategory[] = [
  {
    name: 'Architecture & Blueprint',
    articles: [
      {
        id: 'architecture',
        title: 'System Architecture',
        category: 'Architecture & Blueprint',
        description: 'Modular monolith topology, ports and adapters, and domain boundaries.',
        content: archDoc,
      },
      {
        id: 'repository-structure',
        title: 'Repository Structure',
        category: 'Architecture & Blueprint',
        description: 'Complete codebase hierarchy, modules, and file layout conventions.',
        content: repoDoc,
      },
      {
        id: 'upgrade-evaluation',
        title: 'Upgrade & Dependency Roadmap',
        category: 'Architecture & Blueprint',
        description: 'Evaluations for Spring Boot, Java 21, and third-party library lifecycle.',
        content: upgradeDoc,
      },
    ],
  },
  {
    name: 'API Reference & Integrations',
    articles: [
      {
        id: 'api-reference',
        title: 'REST API Reference',
        category: 'API Reference & Integrations',
        description: 'Complete contracts for candidate, job, application, and operations endpoints.',
        content: apiDoc,
      },
      {
        id: 'webhook-security',
        title: 'Webhook HMAC-SHA256 Security',
        category: 'API Reference & Integrations',
        description: 'Inbound email webhook signature verification and replay defense.',
        content: whDoc,
      },
      {
        id: 'extension-integration',
        title: 'Chrome Extension Integration',
        category: 'API Reference & Integrations',
        description: 'Manifest V3 architecture, intake protocols, and SHA-256 pre-check.',
        content: extDoc,
      },
    ],
  },
  {
    name: 'Database & Data Models',
    articles: [
      {
        id: 'database',
        title: 'Database Schema & Flyway',
        category: 'Database & Data Models',
        description: 'PostgreSQL relational schemas, Flyway migrations, and entity mappings.',
        content: dbDoc,
      },
    ],
  },
  {
    name: 'Operations & Deployment',
    articles: [
      {
        id: 'operations',
        title: 'Observability & Monitoring',
        category: 'Operations & Deployment',
        description: 'Telemetry, correlation IDs, health checks, and worker concurrency.',
        content: opsDoc,
      },
      {
        id: 'deployment',
        title: 'Production Deployment Guide',
        category: 'Operations & Deployment',
        description: 'Docker containerization, environment secrets, and cloud configurations.',
        content: deployDoc,
      },
      {
        id: 'security-guide',
        title: 'Security Architecture & Hardening',
        category: 'Operations & Deployment',
        description: 'Multi-tenant isolation, AES-256-GCM token encryption, and Tika XXE defenses.',
        content: secDoc,
      },
    ],
  },
  {
    name: 'Testing & Quality Gates',
    articles: [
      {
        id: 'testing-strategy',
        title: 'Testing Strategy & Suites',
        category: 'Testing & Quality Gates',
        description: 'Unit, integration, security regression, and E2E verification standards.',
        content: testDoc,
      },
      {
        id: 'test-infrastructure',
        title: 'Test Infrastructure Specification',
        category: 'Testing & Quality Gates',
        description: 'Opaque-box 4-tier requirement test harness and fixtures.',
        content: testInfraDoc,
      },
      {
        id: 'test-ready',
        title: 'Test Readiness Verification',
        category: 'Testing & Quality Gates',
        description: 'Regression readiness criteria and pass certificates.',
        content: testReadyDoc,
      },
    ],
  },
  {
    name: 'Engineering Standards',
    articles: [
      {
        id: 'readme',
        title: 'Project Overview (README)',
        category: 'Engineering Standards',
        description: 'HireFlow AI quickstart, feature overview, and developer commands.',
        content: readmeDoc,
      },
      {
        id: 'agents',
        title: 'Engineering Rules (AGENTS.md)',
        category: 'Engineering Standards',
        description: 'Authoritative rules for architecture, integrity, security, and changes.',
        content: agentsDoc,
      },
      {
        id: 'agent-guide',
        title: 'Multi-Agent Guidelines',
        category: 'Engineering Standards',
        description: 'Collaboration protocol, role boundaries, and audit verification.',
        content: agentGuideDoc,
      },
      {
        id: 'project-spec',
        title: 'Release Specification (PROJECT.md)',
        category: 'Engineering Standards',
        description: 'Feature matrix, milestone roadmap, and interface contracts.',
        content: projectDoc,
      },
    ],
  },
];
