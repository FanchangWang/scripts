"""tests/test_common.py —— common.py 纯函数测试。"""

from __future__ import annotations

import numpy as np

from yolo_chess.common import (
    CELL_OUT,
    CORRECT_H,
    CORRECT_W,
    _parse_adb_devices,
    corrected_center,
    crop_cell,
    dedup_class,
    label_map_for_lift,
    label_map_for_state,
)


class TestCorrectedCenter:
    def test_origin(self) -> None:
        x, y = corrected_center(0, 0)
        assert x == 50.0
        assert y == 50.0

    def test_bottom_right(self) -> None:
        x, y = corrected_center(9, 8)
        assert x == 850.0
        assert y == 950.0

    def test_mid(self) -> None:
        x, y = corrected_center(5, 4)
        assert x == 450.0
        assert y == 550.0


class TestCropCell:
    def test_returns_64x64(self) -> None:
        board = np.random.randint(0, 255, (CORRECT_H, CORRECT_W, 3), dtype=np.uint8)
        cell = crop_cell(board, 5, 4)
        assert cell is not None
        assert cell.shape == (CELL_OUT, CELL_OUT, 3)

    def test_none_on_small_board(self) -> None:
        tiny = np.zeros((10, 10, 3), dtype=np.uint8)
        cell = crop_cell(tiny, 5, 4)
        assert cell is None


class TestLabelMapForState:
    def test_state_1_count(self) -> None:
        m = label_map_for_state("opening")
        assert len(m) == 32

    def test_state_1_has_kings(self) -> None:
        m = label_map_for_state("opening")
        assert m[(0, 4)] == "b_k"
        assert m[(9, 4)] == "r_K"

    def test_state_2_only_kings(self) -> None:
        m = label_map_for_state("mate")
        assert len(m) == 2

    def test_state_3_lift(self) -> None:
        m = label_map_for_state("lift")
        assert m.get((6, 4)) == "lift"

    def test_state_4_empty(self) -> None:
        m = label_map_for_state("endgame")
        assert len(m) == 0


class TestLabelMapForLift:
    def test_default_red_center(self) -> None:
        m = label_map_for_lift(6, 4)
        assert m[(6, 4)] == "lift"
        assert m[(0, 4)] == "b_k"
        assert m[(9, 4)] == "r_K"

    def test_king_as_lift(self) -> None:
        m = label_map_for_lift(9, 4)
        assert m[(9, 4)] == "lift"
        assert (0, 4) in m and m[(0, 4)] == "b_k"
        assert (9, 4) not in m or m[(9, 4)] == "lift"

    def test_no_duplicate_king(self) -> None:
        m = label_map_for_lift(0, 4)
        assert m[(0, 4)] == "lift"
        assert m.get((9, 4)) == "r_K"
        assert m.get((0, 4)) != "b_k"


class TestDedupClass:
    def test_single_item(self) -> None:
        items = [np.zeros((64, 64, 3), dtype=np.uint8)]
        result = dedup_class(items)
        assert len(result) == 1

    def test_identical_items(self) -> None:
        img = np.random.randint(0, 255, (64, 64, 3), dtype=np.uint8)
        items = [img.copy() for _ in range(10)]
        result = dedup_class(items, thresh=0.99)
        assert len(result) <= 2

    def test_different_items(self) -> None:
        items = [np.full((64, 64, 3), i * 10, dtype=np.uint8) for i in range(10)]
        result = dedup_class(items, thresh=0.99)
        assert len(result) >= 2


class TestParseAdbDevices:
    def test_only_header(self) -> None:
        assert _parse_adb_devices("List of devices attached\n") == []

    def test_single_with_model(self) -> None:
        text = (
            "List of devices attached\n"
            "192.168.31.60:5555  device product:raven model:Pixel_6 device:raven\n"
        )
        result = _parse_adb_devices(text)
        assert len(result) == 1
        assert result[0][0] == "192.168.31.60:5555"
        assert "model:Pixel_6" in result[0][1]

    def test_multiple_filters_online(self) -> None:
        text = (
            "List of devices attached\n"
            "emulator-5554  device product:sdk_gphone model:Pixel_7\n"
            "0123456789ABCDEF  device\n"
            "192.168.1.9:5555  offline\n"
        )
        result = _parse_adb_devices(text)
        assert len(result) == 2
        assert result[0][0] == "emulator-5554"
        assert result[1][0] == "0123456789ABCDEF"
        assert result[1][1] == "0123456789ABCDEF"

    def test_unknown_state_ignored(self) -> None:
        text = "List of devices attached\nSOME_SERIAL  unauthorized\n"
        assert _parse_adb_devices(text) == []
