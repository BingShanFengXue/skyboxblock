#!/usr/bin/env python3
"""把 ServerCommandRegression 的权限构造改成 26.2 的 PermissionSet 模型（一次性迁移脚本）。"""
from pathlib import Path

TARGET = Path(__file__).resolve().parent.parent / "src/test/java/dev/yg/skyboxblock/server/ServerCommandRegression.java"

OLD_STACK = """        CommandSourceStack source = new CommandSourceStack(actor, net.minecraft.world.phys.Vec3.ZERO,
                net.minecraft.world.phys.Vec2.ZERO, null, level, name, Component.literal(name), null, null);"""

NEW_STACK = """        // 26.2：权限从 int 等级改成 PermissionSet
        net.minecraft.server.permissions.PermissionSet permissions = level >= 4
                ? net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS
                : net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS;
        CommandSourceStack source = new CommandSourceStack(actor, net.minecraft.world.phys.Vec3.ZERO,
                net.minecraft.world.phys.Vec2.ZERO, null, permissions, name, Component.literal(name), null, null);"""

OLD_CHECK_OP = """        require(operator(new Capture()).hasPermission(SkyboxServerCommands.MUTATION_LEVEL),
                "operator has permission 2");"""
NEW_CHECK_OP = """        require(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(operator(new Capture()).permissions()),
                "operator has permission 2");"""

OLD_CHECK_PLAYER = """        require(!player(new Capture()).hasPermission(SkyboxServerCommands.MUTATION_LEVEL),"""
NEW_CHECK_PLAYER = """        require(!net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(player(new Capture()).permissions()),"""


def main() -> int:
    text = TARGET.read_text(encoding="utf-8")
    for old, new in ((OLD_STACK, NEW_STACK), (OLD_CHECK_OP, NEW_CHECK_OP), (OLD_CHECK_PLAYER, NEW_CHECK_PLAYER)):
        if old not in text:
            print("NOT FOUND:", old.splitlines()[0].strip())
        text = text.replace(old, new)
    TARGET.write_text(text, encoding="utf-8")
    print("patched", TARGET.name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
