/**
 * Pivex $100K challenge account rules — named constants only.
 * Do not replace these with magic numbers in sizing / DD / lock logic.
 */

/** Starting challenge balance (USD). */
export const START_BALANCE = 100000;

/** Closed-profit target (+10%). */
export const TARGET_BALANCE = 110000;

/** Daily drawdown vs start-of-UTC-day equity (floating included). */
export const DAILY_DD_PCT = 0.04;

/**
 * Static overall equity floor. Never moves after recovery —
 * still $94,000 even if equity later climbs back above start.
 */
export const OVERALL_DD_FLOOR = 94000;

/** Distinct UTC calendar days that must contain a logged fill. */
export const MIN_TRADING_DAYS = 5;

/** No single UTC day may exceed this share of total closed profit. */
export const CONSISTENCY_MAX_SHARE = 0.5;

/** Default risk fraction of equity (0.75% — low end of 0.75–1%). */
export const DEFAULT_RISK_PCT = 0.0075;

/** Hard max volume on any ticket (never leave leftover 10.xx lots). */
export const MAX_LOTS = 2.0;

/** Stops tighter than this are spread/noise — reject for pass mode. */
export const MIN_STOP_PIPS = 10;

/** Default reward:risk for take-profit distance. */
export const DEFAULT_RR = 1.8;

/**
 * Leave this fraction of theoretical risk unused so a full stop
 * cannot kiss the daily/overall room.
 */
export const BUFFER_PCT = 0.15;
