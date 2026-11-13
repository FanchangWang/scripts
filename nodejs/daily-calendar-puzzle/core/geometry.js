// 拼板几何：归一化、旋转、翻转、朝向枚举（旋转 + 翻转都允许）

// 归一化到左上角为原点，固定排序便于去重
export function normalize(cells) {
  const minX = Math.min(...cells.map(p => p[0]));
  const minY = Math.min(...cells.map(p => p[1]));
  return cells
    .map(([x, y]) => [x - minX, y - minY])
    .sort((a, b) => a[1] - b[1] || a[0] - b[0]);
}

// 顺时针 90°（右旋）：屏幕坐标 x 向右、y 向下，[x, y] -> [-y, x]
export const rotate = cells => cells.map(([x, y]) => [-y, x]);

// 逆时针 90°（左旋）：屏幕坐标 x 向右、y 向下，[x, y] -> [y, -x]
export const rotateCCW = cells => cells.map(([x, y]) => [y, -x]);

// 水平翻转（左右镜像）：[x, y] -> [-x, y]
export const flip = cells => cells.map(([x, y]) => [-x, y]);

// 垂直翻转（上下镜像）：[x, y] -> [x, -y]
export const flipV = cells => cells.map(([x, y]) => [x, -y]);

// 生成某形状的全部唯一朝向（4 旋转 × 2 翻转，去重）
export function orientations(cells) {
  const out = new Set();
  for (let f = 0; f < 2; f++) {
    let cur = f ? flip(cells) : cells;
    for (let k = 0; k < 4; k++) {
      out.add(JSON.stringify(normalize(cur)));
      cur = rotate(cur);
    }
  }
  return [...out].map(s => JSON.parse(s));
}
