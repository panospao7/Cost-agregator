import { useEffect, useState } from "react";

interface SpendingGaugeProps {
  spent: number;
  budget: number;
  label: string;
}

export function SpendingGauge({ spent, budget, label }: SpendingGaugeProps) {
  const [animatedPercent, setAnimatedPercent] = useState(0);
  const percent = Math.min((spent / budget) * 100, 100);

  useEffect(() => {
    const timer = setTimeout(() => setAnimatedPercent(percent), 200);
    return () => clearTimeout(timer);
  }, [percent]);

  const radius = 80;
  const stroke = 10;
  const normalizedRadius = radius - stroke;
  const circumference = normalizedRadius * Math.PI; // half circle
  const strokeDashoffset = circumference - (animatedPercent / 100) * circumference;

  const getColor = () => {
    if (percent >= 90) return { stroke: "#EF4444", glow: "rgba(239, 68, 68, 0.3)" };
    if (percent >= 75) return { stroke: "#F97316", glow: "rgba(249, 115, 22, 0.3)" };
    return { stroke: "#6366F1", glow: "rgba(99, 102, 241, 0.3)" };
  };

  const color = getColor();
  const daysInMonth = new Date(new Date().getFullYear(), new Date().getMonth() + 1, 0).getDate();
  const currentDay = new Date().getDate();
  const expectedPercent = (currentDay / daysInMonth) * 100;
  const pace = percent <= expectedPercent ? "Under" : "Over";

  return (
    <div className="flex flex-col items-center">
      <svg height={radius + 10} width={radius * 2 + 10} className="overflow-visible">
        <defs>
          <filter id="glow">
            <feGaussianBlur stdDeviation="3" result="coloredBlur" />
            <feMerge>
              <feMergeNode in="coloredBlur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>
        {/* Background arc */}
        <path
          d={`M ${stroke / 2 + 5}, ${radius + 5} A ${normalizedRadius},${normalizedRadius} 0 0 1 ${radius * 2 - stroke / 2 + 5},${radius + 5}`}
          fill="none"
          stroke="rgba(148, 163, 184, 0.1)"
          strokeWidth={stroke}
          strokeLinecap="round"
        />
        {/* Expected pace marker */}
        {(() => {
          const angle = Math.PI - (expectedPercent / 100) * Math.PI;
          const x = radius + 5 + normalizedRadius * Math.cos(angle);
          const y = radius + 5 - normalizedRadius * Math.sin(angle);
          return (
            <circle cx={x} cy={y} r={3} fill="rgba(148, 163, 184, 0.4)" />
          );
        })()}
        {/* Progress arc */}
        <path
          d={`M ${stroke / 2 + 5}, ${radius + 5} A ${normalizedRadius},${normalizedRadius} 0 0 1 ${radius * 2 - stroke / 2 + 5},${radius + 5}`}
          fill="none"
          stroke={color.stroke}
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={strokeDashoffset}
          filter="url(#glow)"
          style={{
            transition: "stroke-dashoffset 1s ease-out, stroke 0.5s ease",
          }}
        />
      </svg>
      <div className="-mt-6 text-center">
        <p className="tabular-nums text-2xl font-bold text-text-primary">
          {Math.round(animatedPercent)}%
        </p>
        <p className="mt-0.5 text-xs text-text-muted">{label}</p>
        <span
          className={`mt-1 inline-block rounded-full px-2 py-0.5 text-[11px] font-semibold ${
            pace === "Under"
              ? "bg-success-dim text-success"
              : "bg-warning-dim text-warning"
          }`}
        >
          {pace} pace
        </span>
      </div>
    </div>
  );
}
