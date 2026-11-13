// 日期 -> 当天需要露出的 3 个格子（月份、日期、星期），坐标 [row, col]

// 星期映射：getDay() 0(周日)~6(周六) -> 棋盘格
const WEEK_MAP = [
  [6, 3], [6, 4], [6, 5], [6, 6],
  [7, 4], [7, 5], [7, 6],
];

export function targetCells(y, m, d) {
  const cells = [];
  // 月份：1-6 在上排，7-12 在下排
  cells.push(m <= 6 ? [0, m - 1] : [1, m - 7]);
  // 日期：从第 3 行（索引 2）开始，每行 7 个
  const i = d - 1;
  cells.push([2 + Math.floor(i / 7), i % 7]);
  // 星期
  const dow = new Date(y, m - 1, d).getDay();
  cells.push(WEEK_MAP[dow]);
  return cells;
}

// 校验日期是否合法（用于游玩/求解前兜底）
export function isValidDate(y, m, d) {
  if (!(y >= 1 && m >= 1 && m <= 12 && d >= 1)) return false;
  const dt = new Date(y, m - 1, d);
  return dt.getFullYear() === y && dt.getMonth() === m - 1 && dt.getDate() === d;
}
