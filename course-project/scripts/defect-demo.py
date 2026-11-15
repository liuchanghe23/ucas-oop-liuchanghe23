#!/usr/bin/env python3
"""HTTP smoke demo: imports static facts without executing faults or workload."""
import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8107")
    parser.add_argument("--source-root", type=Path, help="Optional local OpenTelemetry Demo checkout")
    args = parser.parse_args()
    output = ROOT / ".local" / "defect-transfer-demo" / uuid4().hex
    output.mkdir(parents=True)

    def call(method, path, payload=None):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(args.url.rstrip("/") + "/api/defect-transfer" + path,
                                         data=data, method=method, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"{method} {path}: HTTP {error.code}: {error.read().decode('utf-8')}") from error
        if body.get("success") is not True:
            raise RuntimeError(f"Unsuccessful response: {body}")
        return body["data"]

    def save(name, value):
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    health = call("GET", "/health")
    assert health["executionEnabled"] is False
    markdown = (ROOT / "templates" / "chaosblade_defect_templates.md").read_text(encoding="utf-8")
    imported = call("POST", "/templates/import", {"markdown": markdown})
    assert len(imported) == 15
    assert all(not item["created"] for item in call("POST", "/templates/import", {"markdown": markdown}))
    save("templates-import.json", imported)
    summary = {"createdAt": datetime.now(timezone.utc).isoformat(), "scope": "STATIC_API_SMOKE_ONLY", "cases": []}
    for name, template_id, strategy in [
        ("t11-synthetic", "DT-DUALWRITE-LOST-001", "PRECISE"),
        ("t10-protected-synthetic", "DT-MQ-REDELIVER-001", "PROBABILISTIC"),
    ]:
        profile = call("POST", "/profiles", json.loads((ROOT / "examples" / "defect-transfer" / (name + ".json")).read_text()))
        match = call("POST", "/matches", {"templateId": template_id, "templateVersion": 1, "profileId": profile["id"], "maxBindings": 128})
        assert match["suitability"] == "APPLICABLE"
        index = next(i for i, binding in enumerate(match["bindings"]) if binding["applicability"] == "TRUE")
        payload = json.loads((ROOT / "examples" / "defect-transfer" / "plan-request.json").read_text())
        payload.update(matchId=match["id"], bindingIndex=index, strategy=strategy)
        if template_id == "DT-MQ-REDELIVER-001":
            payload["workload"]["requests"] = 600
            payload["limits"]["maxRequests"] = 1000
        plan = call("POST", "/plans", payload)
        assert plan["executionEnabled"] is False and plan["reviewInputsComplete"] is True
        assert plan["readiness"] == "UNSUPPORTED"
        assert plan["conclusion"].startswith("NOT_EVALUATED")
        assert call("GET", "/plans/" + plan["id"]) == plan
        for kind, item in [("profile", profile), ("match", match), ("plan", plan)]:
            save(name + "-" + kind + ".json", item)
        summary["cases"].append({"name": name, "profileId": profile["id"], "matchId": match["id"], "planId": plan["id"],
                                 "defectConditions": match["bindings"][index]["defectConditions"], "readiness": plan["readiness"], "executionEnabled": False})
    unsupported = call("POST", "/matches", {"templateId": "DT-RETRY-DUP-001", "templateVersion": 1,
                                              "profileId": summary["cases"][0]["profileId"], "maxBindings": 128})
    assert any(item.startswith("RULESET_NOT_IMPLEMENTED") for item in unsupported["limitations"])
    save("unsupported-template-match.json", unsupported)
    if args.source_root:
        source_root = args.source_root.resolve(strict=True)
        revision = subprocess.check_output(["git", "-C", str(source_root), "rev-parse", "HEAD"], text=True).strip()
        dirty = bool(subprocess.check_output(["git", "-C", str(source_root), "status", "--porcelain"], text=True).strip())
        if dirty:
            revision += "-dirty"
        snippets = []
        for entity, path, adapter in [("checkout", "src/checkout/kafka/producer.go", "go"), ("accounting", "src/accounting/Consumer.cs", "dotnet")]:
            snippets.append({"entityId": entity, "path": path, "adapter": adapter, "content": (source_root / path).read_text(encoding="utf-8")})
        result = call("POST", "/profiles/source-hints", {"systemId": "opentelemetry-demo", "revision": revision, "snippets": snippets})
        save("source-hints-profile.json", result)
        profile = result["profile"]
        facts = [fact for entity in profile["snapshot"]["entities"] for fact in entity["facts"].values()]
        assert facts and all(fact["certainty"] == "INFERRED" for fact in facts)
        prescreen = []
        for item in imported:
            prescreen.append(call("POST", "/matches", {"templateId": item["templateId"], "templateVersion": item["version"],
                                                       "profileId": profile["id"], "maxBindings": 128}))
        save("source-prescreen-all15.json", prescreen)
        summary["source"] = {"revision": revision, "dirty": dirty, "profileId": profile["id"], "inferredFacts": len(facts),
                             "suitabilityCounts": {key: sum(m["suitability"] == key for m in prescreen) for key in ("APPLICABLE", "NOT_APPLICABLE", "UNKNOWN")}}
    save("summary.json", summary)
    report = ["# 4.2 第一阶段接口验证", "", "本报告只验证静态匹配流程，没有实际注入故障，也没有认定目标系统存在缺陷。", "",
              "15 条草稿均可导入，重复导入不会新增版本。", "",
              "| 示例 | 静态缺陷条件 | 计划状态 | 实际执行 |", "| --- | --- | --- | --- |"]
    for item in summary["cases"]:
        report.append(f"| {item['name']} | {item['defectConditions']} | {item['readiness']} | 未执行 |")
    report += ["", "UNSUPPORTED 的原因包括：真实执行适配器尚未接入。reviewInputsComplete 只表示用于审核的输入完整。"]
    if "source" in summary:
        report += ["", f"目标源码版本：`{summary['source']['revision']}`。提取 {summary['source']['inferredFacts']} 条待核实线索；没有把线索当作已确认事实。", "",
                   "完整 15 条预筛结果见 source-prescreen-all15.json；未知结果需要补充源码、有效配置和运行证据。"]
    (output / "report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(json.dumps({"result": "PASS", "outputDirectory": str(output), **summary}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, AssertionError, KeyError, StopIteration) as error:
        print(f"Demo failed: {error}", file=sys.stderr)
        sys.exit(1)
