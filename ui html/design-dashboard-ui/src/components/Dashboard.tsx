import { useState } from "react";
import {
  ArrowDownLeft,
  ArrowUpRight,
  Bell,
  CheckCircle2,
  ChevronRight,
  Clock,
  CreditCard,
  Eye,
  MoreHorizontal,
  Smartphone,
  Sparkles,
  TrendingDown,
  TrendingUp,
  Zap,
} from "lucide-react";
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
} from "recharts";
import { SpendingGauge } from "./SpendingGauge";

// Mock data
const spendingData = [
  { day: "1", current: 120, previous: 90 },
  { day: "5", current: 450, previous: 380 },
  { day: "10", current: 890, previous: 720 },
  { day: "15", current: 1340, previous: 1100 },
  { day: "20", current: 1680, previous: 1560 },
  { day: "25", current: 2100, previous: 1890 },
  { day: "30", current: 2450, previous: 2300 },
];

interface Transaction {
  id: string;
  merchant: string;
  category: string;
  amount: number;
  type: "expense" | "income";
  time: string;
  source: string;
  confidence: number;
}

const recentTransactions: Transaction[] = [
  { id: "1", merchant: "Uber Eats", category: "Food & Dining", amount: -18.50, type: "expense", time: "2 min ago", source: "Revolut", confidence: 96 },
  { id: "2", merchant: "Salary Deposit", category: "Income", amount: 3200.00, type: "income", time: "1 hr ago", source: "NBG", confidence: 99 },
  { id: "3", merchant: "Netflix", category: "Entertainment", amount: -15.99, type: "expense", time: "3 hrs ago", source: "Google Wallet", confidence: 92 },
  { id: "4", merchant: "Shell Gas Station", category: "Transportation", amount: -45.20, type: "expense", time: "5 hrs ago", source: "Alpha Bank", confidence: 88 },
  { id: "5", merchant: "Lidl Supermarket", category: "Groceries", amount: -67.30, type: "expense", time: "Yesterday", source: "Revolut", confidence: 91 },
];

interface ReviewItem {
  id: string;
  rawText: string;
  parsedMerchant: string;
  suggestedCategory: string;
  amount: number;
  confidence: number;
}

const reviewItems: ReviewItem[] = [
  { id: "r1", rawText: "POS 14:23 COFFEE ISL SYNTAGMA", parsedMerchant: "Coffee Island", suggestedCategory: "Coffee & Drinks", amount: -4.80, confidence: 72 },
  { id: "r2", rawText: "EUDAP PAYMENT REF8834", parsedMerchant: "EUDAP Parking", suggestedCategory: "Transportation", amount: -2.00, confidence: 58 },
  { id: "r3", rawText: "PHARMACY VITA PLUS GR", parsedMerchant: "Vita Plus Pharmacy", suggestedCategory: "Health", amount: -12.50, confidence: 67 },
];

interface BudgetCategory {
  name: string;
  spent: number;
  limit: number;
  icon: string;
  color: string;
}

const budgets: BudgetCategory[] = [
  { name: "Groceries", spent: 340, limit: 500, icon: "🛒", color: "#10B981" },
  { name: "Dining Out", spent: 180, limit: 200, icon: "🍽️", color: "#F97316" },
  { name: "Transport", spent: 95, limit: 150, icon: "🚗", color: "#6366F1" },
  { name: "Entertainment", spent: 45, limit: 100, icon: "🎮", color: "#8B5CF6" },
  { name: "Shopping", spent: 220, limit: 250, icon: "🛍️", color: "#EC4899" },
];

function CustomTooltip({ active, payload, label }: { active?: boolean; payload?: Array<{ value: number; dataKey: string }>; label?: string }) {
  if (!active || !payload) return null;
  return (
    <div className="glass-card rounded-lg px-3 py-2 shadow-xl">
      <p className="text-[11px] text-text-muted">Day {label}</p>
      {payload.map((p) => (
        <p key={p.dataKey} className="tabular-nums text-sm font-semibold text-text-primary">
          €{p.value.toLocaleString()} <span className="text-[11px] font-normal text-text-muted">{p.dataKey === "current" ? "This month" : "Last month"}</span>
        </p>
      ))}
    </div>
  );
}

export function Dashboard() {
  const [hoveredTx, setHoveredTx] = useState<string | null>(null);

  const totalBudget = 3000;
  const totalSpent = 2100;
  const safeToSpend = totalBudget - totalSpent;
  const daysLeft = 8;
  const dailyBudget = Math.round(safeToSpend / daysLeft);

  return (
    <div className="ml-64 min-h-screen bg-base p-6">
      {/* Header */}
      <header className="mb-6 flex items-center justify-between animate-fade-in">
        <div>
          <h2 className="text-2xl font-bold text-text-primary">
            Good evening, Alex 👋
          </h2>
          <p className="mt-1 text-sm text-text-secondary">
            Here's your financial snapshot for <span className="font-medium text-text-primary">January 2025</span>
          </p>
        </div>
        <div className="flex items-center gap-3">
          {/* Active Service Indicator */}
          <div className="glass-card flex items-center gap-2 rounded-xl px-3 py-2">
            <div className="pulse-dot h-2.5 w-2.5 rounded-full bg-success" />
            <span className="text-xs font-medium text-text-secondary">Capture Active</span>
          </div>
          <button className="relative rounded-xl border border-border bg-surface p-2.5 text-text-secondary transition-colors hover:bg-surface-hover hover:text-text-primary">
            <Bell size={18} />
            <span className="absolute -right-0.5 -top-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-danger text-[10px] font-bold text-white">
              3
            </span>
          </button>
        </div>
      </header>

      {/* Bento Grid */}
      <div className="grid grid-cols-12 gap-4">
        {/* Safe-to-Spend Card */}
        <div className="col-span-12 lg:col-span-5 animate-fade-in animate-fade-in-delay-1">
          <div className="glass-card relative overflow-hidden rounded-2xl p-6">
            <div className="absolute -right-10 -top-10 h-40 w-40 rounded-full bg-primary/5 blur-3xl" />
            <div className="absolute -bottom-8 -left-8 h-32 w-32 rounded-full bg-success/5 blur-3xl" />
            <div className="relative">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary-dim">
                    <Wallet2Icon />
                  </div>
                  <span className="text-sm font-medium text-text-secondary">Safe to Spend</span>
                </div>
                <button className="text-text-muted transition-colors hover:text-text-primary">
                  <Eye size={16} />
                </button>
              </div>
              <p className="tabular-nums mt-4 text-4xl font-extrabold tracking-tight text-text-primary">
                €{safeToSpend.toLocaleString("en", { minimumFractionDigits: 2 })}
              </p>
              <div className="mt-2 flex items-center gap-4">
                <span className="text-sm text-text-muted">
                  <span className="tabular-nums font-semibold text-success">€{dailyBudget}</span>/day for {daysLeft} days
                </span>
              </div>

              {/* Progress bar */}
              <div className="mt-5">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-text-muted">Monthly Progress</span>
                  <span className="tabular-nums font-medium text-text-secondary">
                    €{totalSpent.toLocaleString()} / €{totalBudget.toLocaleString()}
                  </span>
                </div>
                <div className="mt-2 h-2.5 overflow-hidden rounded-full bg-base-lighter/50">
                  <div
                    className="h-full rounded-full bg-gradient-to-r from-primary to-primary-light transition-all duration-1000 ease-out"
                    style={{ width: `${(totalSpent / totalBudget) * 100}%` }}
                  />
                </div>
              </div>

              {/* Quick stats row */}
              <div className="mt-5 grid grid-cols-2 gap-3">
                <div className="rounded-xl bg-base/50 p-3">
                  <div className="flex items-center gap-1.5">
                    <ArrowDownLeft size={14} className="text-success" />
                    <span className="text-[11px] text-text-muted">Income</span>
                  </div>
                  <p className="tabular-nums mt-1 text-lg font-bold text-success">€3,200.00</p>
                </div>
                <div className="rounded-xl bg-base/50 p-3">
                  <div className="flex items-center gap-1.5">
                    <ArrowUpRight size={14} className="text-danger" />
                    <span className="text-[11px] text-text-muted">Expenses</span>
                  </div>
                  <p className="tabular-nums mt-1 text-lg font-bold text-danger">€2,100.00</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Spending Pace Gauge + Quick Stats */}
        <div className="col-span-12 lg:col-span-4 animate-fade-in animate-fade-in-delay-2">
          <div className="glass-card flex h-full flex-col items-center justify-center rounded-2xl p-6">
            <div className="flex w-full items-center justify-between mb-2">
              <h3 className="text-sm font-semibold text-text-secondary">Spending Pace</h3>
              <MoreHorizontal size={16} className="text-text-muted" />
            </div>
            <SpendingGauge spent={2100} budget={3000} label="of monthly budget" />
            <div className="mt-4 grid w-full grid-cols-2 gap-3">
              <div className="flex items-center gap-2 rounded-lg bg-base/50 px-3 py-2">
                <Zap size={14} className="text-warning" />
                <div>
                  <p className="text-[11px] text-text-muted">Avg/Day</p>
                  <p className="tabular-nums text-sm font-bold text-text-primary">€95.45</p>
                </div>
              </div>
              <div className="flex items-center gap-2 rounded-lg bg-base/50 px-3 py-2">
                <CreditCard size={14} className="text-primary-light" />
                <div>
                  <p className="text-[11px] text-text-muted">Transactions</p>
                  <p className="tabular-nums text-sm font-bold text-text-primary">47</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Capture Service Status */}
        <div className="col-span-12 lg:col-span-3 animate-fade-in animate-fade-in-delay-3">
          <div className="glass-card flex h-full flex-col rounded-2xl p-6">
            <h3 className="text-sm font-semibold text-text-secondary">Capture Engine</h3>
            <div className="mt-4 flex-1 space-y-3">
              {[
                { name: "Revolut", status: "active", count: 23 },
                { name: "NBG", status: "active", count: 12 },
                { name: "Google Wallet", status: "active", count: 8 },
                { name: "SMS Gateway", status: "active", count: 4 },
              ].map((src) => (
                <div key={src.name} className="flex items-center justify-between rounded-lg bg-base/50 px-3 py-2">
                  <div className="flex items-center gap-2">
                    <div className="pulse-dot h-2 w-2 rounded-full bg-success" />
                    <span className="text-xs font-medium text-text-primary">{src.name}</span>
                  </div>
                  <span className="tabular-nums text-[11px] text-text-muted">{src.count} txns</span>
                </div>
              ))}
            </div>
            <div className="mt-4 flex items-center gap-2 rounded-lg bg-success-dim px-3 py-2">
              <Smartphone size={14} className="text-success" />
              <span className="text-xs font-medium text-success">All services connected</span>
            </div>
          </div>
        </div>

        {/* Spending Trend Chart */}
        <div className="col-span-12 lg:col-span-8 animate-fade-in animate-fade-in-delay-4">
          <div className="glass-card rounded-2xl p-6">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-sm font-semibold text-text-secondary">Spending Trend</h3>
                <p className="mt-0.5 text-[11px] text-text-muted">Cumulative spending with shadow comparison</p>
              </div>
              <div className="flex items-center gap-4">
                <div className="flex items-center gap-1.5">
                  <div className="h-2 w-6 rounded-full bg-primary" />
                  <span className="text-[11px] text-text-muted">This month</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <div className="h-2 w-6 rounded-full bg-text-muted/30" />
                  <span className="text-[11px] text-text-muted">Last month</span>
                </div>
              </div>
            </div>
            <div className="mt-4 h-56">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={spendingData} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                  <defs>
                    <linearGradient id="colorCurrent" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#6366F1" stopOpacity={0.3} />
                      <stop offset="95%" stopColor="#6366F1" stopOpacity={0} />
                    </linearGradient>
                    <linearGradient id="colorPrevious" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#64748B" stopOpacity={0.15} />
                      <stop offset="95%" stopColor="#64748B" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.06)" />
                  <XAxis
                    dataKey="day"
                    tick={{ fontSize: 11, fill: "#64748B" }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis
                    tick={{ fontSize: 11, fill: "#64748B" }}
                    axisLine={false}
                    tickLine={false}
                    tickFormatter={(v: number) => `€${v}`}
                  />
                  <Tooltip content={<CustomTooltip />} />
                  <Area
                    type="monotone"
                    dataKey="previous"
                    stroke="#64748B"
                    strokeWidth={1.5}
                    strokeDasharray="4 4"
                    fill="url(#colorPrevious)"
                  />
                  <Area
                    type="monotone"
                    dataKey="current"
                    stroke="#6366F1"
                    strokeWidth={2.5}
                    fill="url(#colorCurrent)"
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </div>
        </div>

        {/* Budget Categories */}
        <div className="col-span-12 lg:col-span-4 animate-fade-in animate-fade-in-delay-5">
          <div className="glass-card rounded-2xl p-6">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-text-secondary">Budget Limits</h3>
              <button className="text-[11px] font-medium text-primary-light hover:text-primary transition-colors">
                View All
              </button>
            </div>
            <div className="mt-4 space-y-4">
              {budgets.map((b) => {
                const pct = (b.spent / b.limit) * 100;
                const isWarning = pct >= 75;
                const isDanger = pct >= 90;
                return (
                  <div key={b.name}>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <span className="text-sm">{b.icon}</span>
                        <span className="text-xs font-medium text-text-primary">{b.name}</span>
                      </div>
                      <span className="tabular-nums text-xs text-text-muted">
                        <span className={isDanger ? "text-danger font-semibold" : isWarning ? "text-warning font-semibold" : "text-text-secondary font-semibold"}>
                          €{b.spent}
                        </span>
                        {" / "}€{b.limit}
                      </span>
                    </div>
                    <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-base-lighter/50">
                      <div
                        className="h-full rounded-full transition-all duration-700 ease-out"
                        style={{
                          width: `${Math.min(pct, 100)}%`,
                          backgroundColor: b.color,
                          boxShadow: `0 0 8px ${b.color}40`,
                        }}
                      />
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>

        {/* Recent Transactions */}
        <div className="col-span-12 lg:col-span-7 animate-fade-in animate-fade-in-delay-6">
          <div className="glass-card rounded-2xl p-6">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-semibold text-text-secondary">Recent Transactions</h3>
                <span className="rounded-full bg-primary-dim px-2 py-0.5 text-[11px] font-semibold text-primary-light">
                  Auto-captured
                </span>
              </div>
              <button className="flex items-center gap-1 text-[11px] font-medium text-primary-light hover:text-primary transition-colors">
                See all <ChevronRight size={12} />
              </button>
            </div>
            <div className="mt-4 space-y-1">
              {recentTransactions.map((tx) => (
                <div
                  key={tx.id}
                  className="group flex items-center gap-3 rounded-xl px-3 py-3 transition-all duration-200 hover:bg-surface-hover cursor-pointer"
                  onMouseEnter={() => setHoveredTx(tx.id)}
                  onMouseLeave={() => setHoveredTx(null)}
                >
                  <div
                    className={`flex h-9 w-9 items-center justify-center rounded-xl ${
                      tx.type === "income" ? "bg-success-dim" : "bg-base-lighter/50"
                    }`}
                  >
                    {tx.type === "income" ? (
                      <ArrowDownLeft size={16} className="text-success" />
                    ) : (
                      <ArrowUpRight size={16} className="text-text-muted" />
                    )}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <p className="text-sm font-medium text-text-primary truncate">{tx.merchant}</p>
                      {hoveredTx === tx.id && (
                        <span className="flex items-center gap-1 rounded-full bg-success-dim px-1.5 py-0.5 text-[10px] font-medium text-success">
                          <Sparkles size={9} /> {tx.confidence}%
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="text-[11px] text-text-muted">{tx.category}</span>
                      <span className="text-text-muted/30">·</span>
                      <span className="text-[11px] text-text-muted">{tx.source}</span>
                    </div>
                  </div>
                  <div className="text-right">
                    <p
                      className={`tabular-nums text-sm font-semibold ${
                        tx.type === "income" ? "text-success" : "text-text-primary"
                      }`}
                    >
                      {tx.type === "income" ? "+" : ""}€{Math.abs(tx.amount).toFixed(2)}
                    </p>
                    <p className="text-[11px] text-text-muted">{tx.time}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Review Inbox Preview */}
        <div className="col-span-12 lg:col-span-5 animate-fade-in animate-fade-in-delay-7">
          <div className="glass-card rounded-2xl p-6">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-semibold text-text-secondary">Review Inbox</h3>
                <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-warning-dim px-1.5 text-[11px] font-bold text-warning">
                  {reviewItems.length}
                </span>
              </div>
              <button className="flex items-center gap-1 text-[11px] font-medium text-primary-light hover:text-primary transition-colors">
                Review all <ChevronRight size={12} />
              </button>
            </div>
            <p className="mt-1 text-[11px] text-text-muted">Transactions needing your confirmation</p>
            <div className="mt-4 space-y-3">
              {reviewItems.map((item) => (
                <div
                  key={item.id}
                  className="rounded-xl border border-border bg-base/50 p-3 transition-all duration-200 hover:border-warning/30"
                >
                  <div className="flex items-start justify-between">
                    <div className="min-w-0 flex-1">
                      <p className="text-xs font-medium text-text-primary">{item.parsedMerchant}</p>
                      <p className="mt-0.5 truncate text-[11px] font-mono text-text-muted">{item.rawText}</p>
                    </div>
                    <p className="tabular-nums ml-3 text-sm font-semibold text-text-primary">
                      €{Math.abs(item.amount).toFixed(2)}
                    </p>
                  </div>
                  <div className="mt-2 flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span className="rounded-full bg-base-lighter/50 px-2 py-0.5 text-[10px] font-medium text-text-secondary">
                        {item.suggestedCategory}
                      </span>
                      <span className="flex items-center gap-1 text-[10px] text-warning">
                        <Clock size={9} /> {item.confidence}% conf.
                      </span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <button className="rounded-lg bg-success-dim px-2.5 py-1 text-[11px] font-semibold text-success transition-colors hover:bg-success/25">
                        ✓ Accept
                      </button>
                      <button className="rounded-lg bg-danger-dim px-2.5 py-1 text-[11px] font-semibold text-danger transition-colors hover:bg-danger/25">
                        ✗ Reject
                      </button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Intelligence Engine Stats */}
        <div className="col-span-12 animate-fade-in animate-fade-in-delay-8">
          <div className="glass-card rounded-2xl p-5">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-dim">
                  <Sparkles size={18} className="text-primary-light" />
                </div>
                <div>
                  <h3 className="text-sm font-semibold text-text-primary">Intelligence Engine Summary</h3>
                  <p className="text-[11px] text-text-muted">This month's autonomous performance</p>
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-6">
                <StatPill
                  icon={<CheckCircle2 size={14} className="text-success" />}
                  label="Auto-Accepted"
                  value="41"
                  sub="87% of total"
                  color="text-success"
                />
                <StatPill
                  icon={<Eye size={14} className="text-warning" />}
                  label="Needs Review"
                  value="3"
                  sub="6% of total"
                  color="text-warning"
                />
                <StatPill
                  icon={<TrendingDown size={14} className="text-danger" />}
                  label="Rejected"
                  value="2"
                  sub="4% of total"
                  color="text-danger"
                />
                <StatPill
                  icon={<TrendingUp size={14} className="text-primary-light" />}
                  label="Accuracy"
                  value="93.6%"
                  sub="+2.1% vs last mo."
                  color="text-primary-light"
                />
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function StatPill({
  icon,
  label,
  value,
  sub,
  color,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  sub: string;
  color: string;
}) {
  return (
    <div className="flex items-center gap-2">
      {icon}
      <div>
        <p className="text-[11px] text-text-muted">{label}</p>
        <p className={`tabular-nums text-sm font-bold ${color}`}>{value}</p>
        <p className="text-[10px] text-text-muted">{sub}</p>
      </div>
    </div>
  );
}

function Wallet2Icon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#818CF8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 12V7H5a2 2 0 0 1 0-4h14v4" />
      <path d="M3 5v14a2 2 0 0 0 2 2h16v-5" />
      <path d="M18 12a2 2 0 0 0 0 4h4v-4Z" />
    </svg>
  );
}
