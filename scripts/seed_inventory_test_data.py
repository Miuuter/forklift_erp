from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from decimal import Decimal
from typing import Any

from import_workbook_smoke_data import ApiClient, as_text


CONFIG_ITEMS = [
    {
        "category": "轮胎与底盘",
        "subCategory": "轮胎",
        "itemName": "轮胎类型",
        "itemCode": "TIRE_TYPE",
        "inputType": "SELECT",
        "unit": None,
        "isRequired": True,
        "sortOrder": 10,
        "values": [
            ("SOLID_STANDARD", "实心胎（标准耐磨）", True, 10, "多数室内/厂区工况标准配置"),
            ("PNEUMATIC", "充气胎（室外不平路面）", False, 20, "选配，适合室外粗糙路面"),
            ("NON_MARKING", "白色环保无痕胎", False, 30, "选配，适合食品、医药、洁净车间"),
            ("DUAL_FRONT", "前双轮加宽实心胎", False, 40, "选配，提升重载稳定性"),
        ],
    },
    {
        "category": "货叉与仓储车配置",
        "subCategory": "货叉",
        "itemName": "货叉长度",
        "itemCode": "FORK_LENGTH",
        "inputType": "SELECT",
        "unit": "mm",
        "isRequired": True,
        "sortOrder": 20,
        "values": [
            ("FORK_1220", "1220mm 标准货叉", True, 10, "标准车默认货叉"),
            ("FORK_1070", "1070mm 短货叉", False, 20, "选配，适合窄巷道或小托盘"),
            ("FORK_1520", "1520mm 加长货叉", False, 30, "选配，适合长托盘"),
            ("FORK_1800", "1800mm 超长货叉", False, 40, "选配，适合大件货物"),
        ],
    },
    {
        "category": "门架与属具",
        "subCategory": "门架",
        "itemName": "门架级数与高度",
        "itemCode": "MAST_STAGE",
        "inputType": "SELECT",
        "unit": "mm",
        "isRequired": True,
        "sortOrder": 30,
        "values": [
            ("MAST_2_3000", "二级 3000mm 标准门架", True, 10, "标准仓库常用配置"),
            ("MAST_2_4000", "二级 4000mm 加高门架", False, 20, "选配，加高堆垛"),
            ("MAST_3_4500", "三级 4500mm 全自由门架", False, 30, "选配，集装箱/高位货架"),
            ("MAST_3_4800", "三级 4800mm 全自由门架", False, 40, "选配，高位货架测试"),
        ],
    },
    {
        "category": "动力与传动系统",
        "subCategory": "电池",
        "itemName": "电池/启动电源",
        "itemCode": "BATTERY_PACK",
        "inputType": "SELECT",
        "unit": None,
        "isRequired": True,
        "sortOrder": 40,
        "values": [
            ("BATTERY_STANDARD", "标准免维护启动电池 / 80V205Ah 锂电", True, 10, "按车型自动理解为内燃启动电池或电车标准锂电"),
            ("LITHIUM_80V300", "80V300Ah 大容量锂电池", False, 20, "选配，长续航"),
            ("LEAD_ACID_80V500", "80V500Ah 铅酸电池", False, 30, "选配，传统电瓶车测试"),
            ("FAST_SWAP", "快换电池托架", False, 40, "选配，多班制测试"),
        ],
    },
    {
        "category": "动力与传动系统",
        "subCategory": "充电器",
        "itemName": "充电器规格",
        "itemCode": "CHARGER_SPEC",
        "inputType": "SELECT",
        "unit": None,
        "isRequired": False,
        "sortOrder": 50,
        "values": [
            ("CHARGER_STANDARD", "标准匹配充电器", True, 10, "电动车默认随车配置"),
            ("CHARGER_FAST_100A", "外置快充 80V100A", False, 20, "选配，快充测试"),
            ("CHARGER_DUAL", "双枪智能充电器", False, 30, "选配，多车位测试"),
        ],
    },
    {
        "category": "门架与属具",
        "subCategory": "属具",
        "itemName": "侧移器/属具",
        "itemCode": "ATTACHMENT",
        "inputType": "SELECT",
        "unit": None,
        "isRequired": False,
        "sortOrder": 60,
        "values": [
            ("NO_ATTACHMENT", "无属具（标准货叉架）", True, 10, "标准车默认"),
            ("SIDE_SHIFTER", "液压侧移器", False, 20, "选配，仓储高频配置"),
            ("FORK_POSITIONER", "调距叉", False, 30, "选配，多规格托盘"),
            ("CLAMP", "纸卷夹/抱夹预留油路", False, 40, "选配属具测试"),
        ],
    },
    {
        "category": "车身配置与附件",
        "subCategory": "安全",
        "itemName": "安全附件",
        "itemCode": "SAFETY_KIT",
        "inputType": "SELECT",
        "unit": None,
        "isRequired": True,
        "sortOrder": 70,
        "values": [
            ("SAFETY_STANDARD", "标准灯光 + 倒车蜂鸣器", True, 10, "标准安全包"),
            ("BLUE_LIGHT", "蓝光警示灯 + 后扶手喇叭", False, 20, "选配，人员密集区域"),
            ("CAMERA", "倒车影像 + 行车记录仪", False, 30, "选配，精细管理"),
        ],
    },
    {
        "category": "车身配置与附件",
        "subCategory": "驾驶室",
        "itemName": "驾驶室/护顶架",
        "itemCode": "CABIN_TYPE",
        "inputType": "SELECT",
        "unit": None,
        "isRequired": True,
        "sortOrder": 80,
        "values": [
            ("OPEN_GUARD", "标准护顶架", True, 10, "标准车默认"),
            ("RAIN_COVER", "雨棚 + 前挡风", False, 20, "选配，室外工况"),
            ("FULL_CABIN", "全封闭驾驶室", False, 30, "选配，冷库/雨天测试"),
        ],
    },
]


PARTS = [
    ("TL-PART-TIRE-SOLID-65010", "龙工", "实心轮胎 6.50-10", "6.50-10 标准耐磨", "轮胎", "FD20/FD25/FD30", "外购备件", 48, "条", "480.00", "780.00", "620.00"),
    ("TL-PART-TIRE-SOLID-70012", "龙工", "实心轮胎 7.00-12", "7.00-12 重载耐磨", "轮胎", "FD30/FD35", "外购备件", 36, "条", "620.00", "980.00", "780.00"),
    ("TL-PART-TIRE-NONMARK-65010", "正新", "白色无痕轮胎", "6.50-10 环保无痕", "轮胎", "电动平衡重式叉车", "选配备件", 20, "条", "760.00", "1180.00", "960.00"),
    ("TL-PART-FORK-1220-3T", "天力", "标准货叉", "3T 1220mm", "货叉", "2.5T/3T/3.5T", "本地库存", 18, "副", "900.00", "1450.00", "1180.00"),
    ("TL-PART-FORK-1520-3T", "天力", "加长货叉", "3T 1520mm", "货叉", "2.5T/3T/3.5T", "选配备件", 10, "副", "1250.00", "1900.00", "1580.00"),
    ("TL-PART-FORK-1800-3T", "天力", "超长货叉", "3T 1800mm", "货叉", "3T/3.5T", "选配备件", 6, "副", "1680.00", "2600.00", "2150.00"),
    ("TL-PART-BAT-LI-80V205", "凡己", "锂电池组", "80V205Ah", "电池", "CPD20/CPD25", "电池仓", 8, "组", "8500.00", "12800.00", "10600.00"),
    ("TL-PART-BAT-LI-80V300", "凡己", "大容量锂电池组", "80V300Ah", "电池", "CPD25/CPD30", "选配备件", 5, "组", "11800.00", "17800.00", "14600.00"),
    ("TL-PART-BAT-START-12V80", "骆驼", "免维护启动电池", "12V80Ah", "电池", "内燃平衡重式叉车", "常用备件", 24, "个", "360.00", "580.00", "460.00"),
    ("TL-PART-CHG-80V100A", "安德普", "外置快充充电器", "80V100A", "充电器", "CPD20/CPD25/CPD30", "电池仓", 6, "台", "2900.00", "4500.00", "3650.00"),
    ("TL-PART-CHG-48V60A", "安德普", "标准充电器", "48V60A", "充电器", "托盘搬运车/堆高车", "电池仓", 12, "台", "980.00", "1580.00", "1260.00"),
    ("TL-PART-ATT-SIDESHIFT-3T", "中联属具", "液压侧移器", "3T ISO II", "属具", "FD25/FD30/CPD30", "选配备件", 7, "套", "2400.00", "3800.00", "3150.00"),
    ("TL-PART-ATT-FORKPOS-3T", "中联属具", "调距叉", "3T 侧移调距", "属具", "FD30/FD35", "选配备件", 4, "套", "5200.00", "7800.00", "6500.00"),
    ("TL-PART-HYD-SEAL-KIT", "龙工", "液压油缸密封包", "倾斜/转向常用包", "液压件", "通用", "维修备件", 40, "套", "85.00", "180.00", "135.00"),
    ("TL-PART-HYD-HOSE-12", "天力", "高压油管", "12mm 标准接头", "液压件", "通用", "维修备件", 60, "根", "65.00", "150.00", "110.00"),
    ("TL-PART-FILTER-DIESEL", "龙工", "柴油滤芯", "国三/国四通用", "保养件", "内燃平衡重式叉车", "保养库存", 80, "个", "35.00", "75.00", "55.00"),
    ("TL-PART-FILTER-HYD", "龙工", "液压油滤芯", "回油滤芯", "保养件", "通用", "保养库存", 75, "个", "42.00", "95.00", "70.00"),
    ("TL-PART-BRAKE-SHOE", "龙工", "制动蹄片", "3T 后轮制动", "制动件", "FD25/FD30/FD35", "维修备件", 30, "套", "180.00", "320.00", "250.00"),
    ("TL-PART-LIGHT-BLUE", "天力", "蓝光警示灯", "12V/24V 通用", "安全附件", "通用", "安全库存", 28, "个", "95.00", "220.00", "160.00"),
    ("TL-PART-CAMERA-KIT", "天力", "倒车影像套装", "7寸屏 + 摄像头", "安全附件", "通用", "安全库存", 10, "套", "450.00", "880.00", "690.00"),
    ("TL-PART-SEAT-BELT", "天力", "安全带总成", "叉车通用", "安全附件", "通用", "维修备件", 35, "套", "55.00", "120.00", "88.00"),
    ("TL-PART-CONTROLLER-CPD", "凡己", "电控总成", "80V 交流控制器", "电控件", "CPD20/CPD25/CPD30", "关键备件", 3, "套", "3800.00", "6200.00", "5100.00"),
    ("TL-PART-MAST-CHAIN-3T", "龙工", "门架链条", "3T 标准链条", "门架件", "FD25/FD30/CPD30", "维修备件", 16, "条", "260.00", "520.00", "390.00"),
    ("TL-PART-VALVE-4WAY", "龙工", "四联多路阀", "3T 标准", "液压件", "FD30/FD35", "关键备件", 5, "台", "1450.00", "2600.00", "2100.00"),
]


@dataclass
class SeedStats:
    configItemsCreated: int = 0
    configItemsReused: int = 0
    configValuesCreated: int = 0
    configValuesReused: int = 0
    vehiclesSeen: int = 0
    vehiclesRestocked: int = 0
    vehicleConfigsUpdated: int = 0
    standardVehicles: int = 0
    optionalVehicles: int = 0
    partsCreated: int = 0
    partsUpdated: int = 0
    partsReused: int = 0


class SeedClient(ApiClient):
    def get_config_items(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/config/items")

    def create_config_item(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request("POST", "/api/config/items", payload)

    def get_config_values(self, item_id: int) -> list[dict[str, Any]]:
        return self._request("GET", f"/api/config/items/{item_id}/values")

    def create_config_value(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request("POST", "/api/config/values", payload)

    def list_machines(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/inventory?paged=false")

    def inbound_machine(self, machine_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request("PUT", f"/api/inventory/{machine_id}/inbound", payload)

    def update_machine_configs(self, machine_id: int, version: int | None, payload: list[dict[str, Any]]) -> list[dict[str, Any]]:
        suffix = f"?version={version}" if version is not None else ""
        return self._request("PUT", f"/api/inventory/{machine_id}/configs{suffix}", payload)

    def list_parts(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/parts?paged=false")

    def create_part(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request("POST", "/api/parts", payload)

    def update_part(self, part_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request("PUT", f"/api/parts/{part_id}", payload)

    def machine_detail(self, machine_id: int) -> dict[str, Any]:
        return self._request("GET", f"/api/inventory/{machine_id}/detail")


def money(value: str) -> str:
    return format(Decimal(value).quantize(Decimal("0.01")), "f")


def ensure_config_catalog(client: SeedClient, stats: SeedStats) -> dict[str, dict[str, Any]]:
    items_by_code = {
        item["itemCode"]: item
        for item in client.get_config_items()
        if item.get("itemCode")
    }
    result: dict[str, dict[str, Any]] = {}
    for item_spec in CONFIG_ITEMS:
        item = items_by_code.get(item_spec["itemCode"])
        if item is None:
            item_payload = {key: value for key, value in item_spec.items() if key != "values"}
            item = client.create_config_item(item_payload)
            stats.configItemsCreated += 1
        else:
            stats.configItemsReused += 1

        values = client.get_config_values(item["id"])
        values_by_code = {
            value.get("valueCode") or value.get("valueLabel"): value
            for value in values
        }
        default_value: dict[str, Any] | None = None
        optional_values: list[dict[str, Any]] = []
        for value_code, value_label, is_default, sort_order, remark in item_spec["values"]:
            value = values_by_code.get(value_code)
            if value is None:
                value = client.create_config_value({
                    "configItemId": item["id"],
                    "valueLabel": value_label,
                    "valueCode": value_code,
                    "isDefault": is_default,
                    "sortOrder": sort_order,
                    "remark": remark,
                })
                stats.configValuesCreated += 1
            else:
                stats.configValuesReused += 1
            if is_default:
                default_value = value
            else:
                optional_values.append(value)

        if default_value is None:
            raise RuntimeError(f"default config value missing for {item_spec['itemCode']}")
        result[item_spec["itemCode"]] = {
            "item": item,
            "default": default_value,
            "optional": optional_values,
        }
    return result


def machine_rank(machine: dict[str, Any]) -> int:
    value = as_text(machine.get("vehicleProductNumber")) or str(machine.get("id"))
    return sum(ord(ch) for ch in value)


def choose_vehicle_configs(machine: dict[str, Any], catalog: dict[str, dict[str, Any]], optional: bool) -> list[dict[str, Any]]:
    rank = machine_rank(machine)
    configs: list[dict[str, Any]] = []
    for index, key in enumerate(catalog.keys()):
        entry = catalog[key]
        item = entry["item"]
        selected = entry["default"]
        source = "FACTORY_STANDARD"
        is_standard = True
        remark = "测试数据：标准车默认配置"
        if optional and entry["optional"] and index in {1, 2, 3, 5, 6, 7}:
            options = entry["optional"]
            selected = options[(rank + index) % len(options)]
            source = "FACTORY_OPTIONAL"
            is_standard = False
            remark = "测试数据：选配车特殊配置"
        configs.append({
            "configItemId": item["id"],
            "configValueId": selected["id"],
            "itemName": item["itemName"],
            "selectedValue": selected["valueLabel"],
            "isStandard": is_standard,
            "configSource": source,
            "remark": remark,
        })
    return configs


def enrich_vehicle_stock_and_configs(
    client: SeedClient,
    catalog: dict[str, dict[str, Any]],
    stats: SeedStats,
    target_instock: int,
    optional_every: int,
) -> None:
    machines = sorted(client.list_machines(), key=lambda item: (item.get("id") or 0))
    stats.vehiclesSeen = len(machines)
    current_instock = sum(1 for machine in machines if (machine.get("inventoryCount") or 0) > 0)
    restock_needed = max(0, target_instock - current_instock)

    refreshed: list[dict[str, Any]] = []
    for machine in machines:
        if restock_needed > 0 and (machine.get("inventoryCount") or 0) <= 0:
            machine = client.inbound_machine(machine["id"], {
                "version": machine.get("version"),
                "quantity": 1,
                "operator": "seed-inventory-test-data",
                "remark": "虚拟测试库存补录：保留历史出库订单，同时补充可测试在库车辆",
            })
            stats.vehiclesRestocked += 1
            restock_needed -= 1
        refreshed.append(machine)

    for index, machine in enumerate(refreshed):
        optional = optional_every > 0 and (index + 1) % optional_every == 0
        configs = choose_vehicle_configs(machine, catalog, optional)
        client.update_machine_configs(machine["id"], machine.get("version"), configs)
        stats.vehicleConfigsUpdated += 1
        if optional:
            stats.optionalVehicles += 1
        else:
            stats.standardVehicles += 1


def part_payload(part: tuple[Any, ...]) -> dict[str, Any]:
    code, brand, name, spec, category, models, source, quantity, unit, purchase, sale, settlement = part
    return {
        "partCode": code,
        "partBrand": brand,
        "partName": name,
        "specification": spec,
        "partCategory": category,
        "applicableModels": models,
        "source": source,
        "quantity": quantity,
        "unit": unit,
        "purchasePrice": money(purchase),
        "salePrice": money(sale),
        "settlementPrice": money(settlement),
        "remarks": "测试数据：用于配件入库、出库、维修和替换流程验证",
    }


def seed_parts(client: SeedClient, stats: SeedStats) -> None:
    existing_by_code = {
        part["partCode"]: part
        for part in client.list_parts()
        if part.get("partCode")
    }
    for part in PARTS:
        payload = part_payload(part)
        existing = existing_by_code.get(payload["partCode"])
        if existing is None:
            client.create_part(payload)
            stats.partsCreated += 1
            continue
        payload["version"] = existing.get("version")
        changed = any(existing.get(key) != payload.get(key) for key in (
            "partBrand",
            "partName",
            "specification",
            "partCategory",
            "applicableModels",
            "source",
            "quantity",
            "unit",
            "remarks",
        ))
        if changed:
            client.update_part(existing["id"], payload)
            stats.partsUpdated += 1
        else:
            stats.partsReused += 1


def run_seed(args: argparse.Namespace) -> dict[str, Any]:
    client = SeedClient(args.base_url, args.username, args.password)
    stats = SeedStats()
    catalog = ensure_config_catalog(client, stats)
    enrich_vehicle_stock_and_configs(client, catalog, stats, args.target_instock, args.optional_every)
    seed_parts(client, stats)

    final_machines = client.list_machines()
    final_parts = client.list_parts()
    final_config_items = client.get_config_items()
    sample_machine = next((machine for machine in final_machines if machine.get("id")), None)
    sample_detail = client.machine_detail(sample_machine["id"]) if sample_machine else None
    return {
        "baseUrl": args.base_url,
        "stats": asdict(stats),
        "finalCounts": {
            "vehicles": len(final_machines),
            "inStockVehicles": sum(1 for machine in final_machines if (machine.get("inventoryCount") or 0) > 0),
            "configItems": len(final_config_items),
            "parts": len(final_parts),
            "partTotalQuantity": sum(part.get("quantity") or 0 for part in final_parts),
            "sampleVehicleConfigCount": len(sample_detail.get("configs", [])) if sample_detail else 0,
            "sampleVehicle": sample_detail["machine"]["vehicleProductNumber"] if sample_detail else None,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed realistic inventory, vehicle configs, and parts for ERP testing.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin123")
    parser.add_argument("--target-instock", type=int, default=240, help="Target number of vehicles with positive stock.")
    parser.add_argument("--optional-every", type=int, default=5, help="Every Nth vehicle becomes an optional-config vehicle.")
    args = parser.parse_args()
    print(json.dumps(run_seed(args), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
