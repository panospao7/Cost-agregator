import {
  LayoutDashboard,
  Inbox,
  TrendingUp,
  Receipt,
  Settings,
  Shield,
  HelpCircle,
  Wallet,
} from "lucide-react";
import { cn } from "@/utils/cn";

interface NavItem {
  icon: React.ReactNode;
  label: string;
  active?: boolean;
  badge?: number;
}

const mainNav: NavItem[] = [
  { icon: <LayoutDashboard size={20} />, label: "Dashboard", active: true },
  { icon: <Inbox size={20} />, label: "Review Inbox", badge: 3 },
  { icon: <TrendingUp size={20} />, label: "Plan & Budget" },
  { icon: <Receipt size={20} />, label: "Receipt Center" },
];

const secondaryNav: NavItem[] = [
  { icon: <Settings size={20} />, label: "Settings" },
  { icon: <HelpCircle size={20} />, label: "Help & Info" },
];

function NavButton({ icon, label, active, badge }: NavItem) {
  return (
    <button
      className={cn(
        "group flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all duration-200",
        active
          ? "bg-primary/15 text-primary-light shadow-sm shadow-primary/10"
          : "text-text-secondary hover:bg-surface-hover hover:text-text-primary"
      )}
    >
      <span className={cn(active ? "text-primary-light" : "text-text-muted group-hover:text-text-secondary")}>
        {icon}
      </span>
      <span className="flex-1 text-left">{label}</span>
      {badge && (
        <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-danger px-1.5 text-[11px] font-semibold text-white">
          {badge}
        </span>
      )}
    </button>
  );
}

export function Sidebar() {
  return (
    <aside className="fixed left-0 top-0 z-30 flex h-screen w-64 flex-col border-r border-border bg-base px-4 py-6">
      {/* Logo */}
      <div className="mb-8 flex items-center gap-3 px-2">
        <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-primary to-purple-500 shadow-lg shadow-primary/25">
          <Wallet size={18} className="text-white" />
        </div>
        <div>
          <h1 className="text-base font-bold tracking-tight text-text-primary">ExpenseTracker</h1>
          <p className="text-[11px] font-medium text-text-muted">Financial Co-Pilot</p>
        </div>
      </div>

      {/* Main Nav */}
      <nav className="flex-1 space-y-1">
        <p className="mb-2 px-3 text-[11px] font-semibold uppercase tracking-wider text-text-muted">
          Main
        </p>
        {mainNav.map((item) => (
          <NavButton key={item.label} {...item} />
        ))}

        <div className="my-4 border-t border-border" />

        <p className="mb-2 px-3 text-[11px] font-semibold uppercase tracking-wider text-text-muted">
          System
        </p>
        {secondaryNav.map((item) => (
          <NavButton key={item.label} {...item} />
        ))}
      </nav>

      {/* Privacy Badge */}
      <div className="mt-4 rounded-xl border border-border bg-surface p-3">
        <div className="flex items-center gap-2">
          <Shield size={16} className="text-success" />
          <span className="text-xs font-semibold text-success">Local-Only Mode</span>
        </div>
        <p className="mt-1.5 text-[11px] leading-relaxed text-text-muted">
          Zero-cloud architecture. Your data never leaves this device.
        </p>
      </div>
    </aside>
  );
}
