import React, { useState, useMemo } from 'react';
import {
  BookOpen,
  Search,
  Copy,
  Check,
  ChevronRight,
  ExternalLink,
  Code2,
  FileText,
  Bookmark,
  Share2,
} from 'lucide-react';
import { DOC_CATEGORIES, DocArticle } from '../docs-data/docsRegistry';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { useToast } from '../components/common/Toast';

export const DocsPage: React.FC = () => {
  const { toast } = useToast();

  const allArticles = useMemo(() => {
    return DOC_CATEGORIES.flatMap((c) => c.articles);
  }, []);

  const [activeArticleId, setActiveArticleId] = useState<string>(allArticles[0]?.id || 'architecture');
  const [searchFilter, setSearchFilter] = useState('');
  const [copiedCodeIndex, setCopiedCodeIndex] = useState<number | null>(null);

  const activeArticle = useMemo(() => {
    return allArticles.find((a) => a.id === activeArticleId) || allArticles[0];
  }, [allArticles, activeArticleId]);

  const filteredCategories = useMemo(() => {
    if (!searchFilter.trim()) return DOC_CATEGORIES;
    const lower = searchFilter.toLowerCase();
    return DOC_CATEGORIES.map((cat) => ({
      ...cat,
      articles: cat.articles.filter(
        (a) =>
          a.title.toLowerCase().includes(lower) ||
          a.description.toLowerCase().includes(lower) ||
          a.id.toLowerCase().includes(lower)
      ),
    })).filter((cat) => cat.articles.length > 0);
  }, [searchFilter]);

  const handleCopyCode = (codeText: string, index: number) => {
    navigator.clipboard.writeText(codeText);
    setCopiedCodeIndex(index);
    toast('Code snippet copied to clipboard', 'success');
    setTimeout(() => setCopiedCodeIndex(null), 2500);
  };

  // Render markdown content cleanly
  const renderMarkdown = (content: string) => {
    const lines = content.split('\n');
    const elements: React.ReactNode[] = [];
    let inCodeBlock = false;
    let codeBlockLang = '';
    let codeBuffer: string[] = [];
    let codeIndex = 0;
    let tableBuffer: string[] = [];

    const flushTable = (key: string) => {
      if (tableBuffer.length === 0) return;
      const rows = tableBuffer.map((r) =>
        r
          .split('|')
          .slice(1, -1)
          .map((c) => c.trim())
      );
      tableBuffer = [];
      if (rows.length < 2) return;

      const headerRow = rows[0];
      const dataRows = rows.slice(2); // row 1 is separator |---|---|

      elements.push(
        <div key={key} className="overflow-x-auto my-6 border border-slate-200 dark:border-slate-800 rounded-xl">
          <table className="w-full text-left text-xs border-collapse">
            <thead className="bg-slate-50 dark:bg-slate-900 border-b border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-300 font-semibold">
              <tr>
                {headerRow.map((h, i) => (
                  <th key={i} className="py-2.5 px-4">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800/60 bg-white dark:bg-slate-950/40">
              {dataRows.map((row, rIdx) => (
                <tr key={rIdx} className="hover:bg-slate-50 dark:hover:bg-slate-900/30">
                  {row.map((cell, cIdx) => (
                    <td key={cIdx} className="py-2 px-4 text-slate-700 dark:text-slate-300">
                      {cell}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    };

    lines.forEach((line, idx) => {
      // Code Block check
      if (line.startsWith('```')) {
        if (inCodeBlock) {
          // Flush code block
          const codeString = codeBuffer.join('\n');
          const currentCodeIdx = codeIndex++;
          elements.push(
            <div key={`code-${idx}`} className="my-5 rounded-xl overflow-hidden border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-950">
              <div className="px-4 py-2 bg-slate-100 dark:bg-slate-900/80 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between text-[11px] text-slate-600 dark:text-slate-400 font-mono">
                <span>{codeBlockLang || 'text'}</span>
                <button
                  onClick={() => handleCopyCode(codeString, currentCodeIdx)}
                  className="flex items-center gap-1.5 text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-white px-2 py-1 rounded hover:bg-slate-200 dark:hover:bg-slate-800 transition-colors"
                >
                  {copiedCodeIndex === currentCodeIdx ? (
                    <>
                      <Check className="w-3.5 h-3.5 text-teal-600 dark:text-teal-400" />
                      <span className="text-teal-600 dark:text-teal-400">Copied</span>
                    </>
                  ) : (
                    <>
                      <Copy className="w-3.5 h-3.5" />
                      <span>Copy</span>
                    </>
                  )}
                </button>
              </div>
              <pre className="p-4 text-xs font-mono text-teal-700 dark:text-teal-300 overflow-x-auto whitespace-pre leading-relaxed">
                {codeString}
              </pre>
            </div>
          );
          codeBuffer = [];
          inCodeBlock = false;
        } else {
          inCodeBlock = true;
          codeBlockLang = line.replace('```', '').trim();
        }
        return;
      }

      if (inCodeBlock) {
        codeBuffer.push(line);
        return;
      }

      // Table line check
      if (line.trim().startsWith('|') && line.trim().endsWith('|')) {
        tableBuffer.push(line.trim());
        return;
      } else if (tableBuffer.length > 0) {
        flushTable(`table-${idx}`);
      }

      // Headers
      if (line.startsWith('# ')) {
        elements.push(
          <h1 key={idx} className="text-2xl font-bold text-slate-900 dark:text-white tracking-tight mt-8 mb-4 border-b border-slate-200 dark:border-slate-800 pb-3">
            {line.replace('# ', '')}
          </h1>
        );
        return;
      }
      if (line.startsWith('## ')) {
        elements.push(
          <h2 key={idx} className="text-lg font-bold text-slate-800 dark:text-slate-100 tracking-tight mt-6 mb-3">
            {line.replace('## ', '')}
          </h2>
        );
        return;
      }
      if (line.startsWith('### ')) {
        elements.push(
          <h3 key={idx} className="text-sm font-semibold text-teal-700 dark:text-teal-400 mt-5 mb-2">
            {line.replace('### ', '')}
          </h3>
        );
        return;
      }

      // Blockquote
      if (line.startsWith('> ')) {
        elements.push(
          <blockquote
            key={idx}
            className="my-3 pl-4 border-l-2 border-teal-500 text-slate-600 dark:text-slate-400 italic text-xs py-1 bg-slate-50 dark:bg-slate-900/30 rounded-r-lg"
          >
            {line.replace('> ', '')}
          </blockquote>
        );
        return;
      }

      // Horizontal Rule
      if (line.trim() === '---' || line.trim() === '***') {
        elements.push(<hr key={idx} className="border-slate-200 dark:border-slate-800 my-6" />);
        return;
      }

      // Bullet List
      if (line.startsWith('- ') || line.startsWith('* ')) {
        elements.push(
          <li key={idx} className="text-xs text-slate-700 dark:text-slate-300 ml-4 list-disc my-1 leading-relaxed">
            {line.substring(2)}
          </li>
        );
        return;
      }

      // Empty Line
      if (!line.trim()) {
        return;
      }

      // Regular Paragraph
      elements.push(
        <p key={idx} className="text-xs text-slate-700 dark:text-slate-300 my-2 leading-relaxed">
          {line}
        </p>
      );
    });

    if (tableBuffer.length > 0) {
      flushTable('table-final');
    }

    return elements;
  };

  return (
    <div className="flex flex-col lg:flex-row gap-6 animate-in fade-in duration-300">
      {/* Left Documentation Catalog Sidebar */}
      <div className="w-full lg:w-72 shrink-0 space-y-4">
        {/* Search in Docs */}
        <div className="relative">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 dark:text-slate-500" />
          <input
            type="text"
            value={searchFilter}
            onChange={(e) => setSearchFilter(e.target.value)}
            placeholder="Search documentation..."
            className="w-full pl-9 pr-3 py-2 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl text-xs text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 focus:outline-none focus:border-teal-600 dark:focus:border-teal-400 shadow-sm"
          />
        </div>

        {/* Categories Accordion */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-3 divide-y divide-slate-100 dark:divide-slate-800/80 shadow-sm dark:shadow-lg">
          {filteredCategories.map((cat, cIdx) => (
            <div key={cat.name} className={cIdx > 0 ? 'pt-3 mt-3' : ''}>
              <span className="text-[11px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 px-2 block mb-1.5">
                {cat.name}
              </span>
              <div className="space-y-0.5">
                {cat.articles.map((art) => {
                  const isActive = art.id === activeArticleId;
                  return (
                    <button
                      key={art.id}
                      onClick={() => setActiveArticleId(art.id)}
                      className={`w-full text-left px-3 py-2 rounded-xl text-xs transition-colors flex items-center justify-between group ${
                        isActive
                          ? 'bg-teal-50 dark:bg-teal-950/60 border border-teal-200 dark:border-teal-800/60 text-teal-800 dark:text-teal-300 font-semibold'
                          : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800/50'
                      }`}
                    >
                      <span className="truncate">{art.title}</span>
                      <ChevronRight
                        className={`w-3.5 h-3.5 shrink-0 transition-transform ${
                          isActive ? 'text-teal-600 dark:text-teal-400' : 'text-slate-400 group-hover:text-slate-600 dark:group-hover:text-slate-400'
                        }`}
                      />
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Main Documentation Viewer Canvas */}
      <div className="flex-1 min-w-0 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-6 sm:p-8 shadow-sm dark:shadow-xl">
        {/* Document Header */}
        <div className="border-b border-slate-200 dark:border-slate-800 pb-5 mb-6 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2 mb-1">
              <Badge variant="indigo" size="sm">
                {activeArticle.category}
              </Badge>
              <span className="text-slate-400 dark:text-slate-500 text-xs">•</span>
              <span className="text-[11px] text-slate-500 dark:text-slate-400 font-mono">docs/{activeArticle.id}.md</span>
            </div>
            <h1 className="text-2xl font-bold tracking-tight text-slate-900 dark:text-white">{activeArticle.title}</h1>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">{activeArticle.description}</p>
          </div>

          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                navigator.clipboard.writeText(window.location.href);
                toast('Document link copied to clipboard', 'success');
              }}
              icon={<Share2 className="w-3.5 h-3.5" />}
            >
              Share
            </Button>
          </div>
        </div>

        {/* Rendered Markdown Body */}
        <article className="prose prose-slate dark:prose-invert max-w-none text-slate-700 dark:text-slate-300 text-xs">
          {renderMarkdown(activeArticle.content)}
        </article>
      </div>
    </div>
  );
};
