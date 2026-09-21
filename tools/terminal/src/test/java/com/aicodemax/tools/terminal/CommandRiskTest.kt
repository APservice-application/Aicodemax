package com.aicodemax.tools.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandRiskTest {
    @Test
    fun catastrophicCommandsAreBanned() {
        val banned = listOf(
            "rm -rf /",
            "rm -rf / --no-preserve-root",
            "rm -fr /tmp /",
            "rm -rf ~",
            "dd if=x of=/dev/block/mmcblk0",
            "mkfs.ext4 /dev/sda1",
            "chmod -R 777 /",
            ":(){ :|:& };:",
            "drop table users",
            "TRUNCATE DATABASE prod",
            "sudo rm -rf /tmp/x",
            "su -c id",
        )
        for (cmd in banned) {
            assertEquals(cmd, CommandRisk.BANNED, CommandRiskClassifier.classify(cmd))
        }
    }

    @Test
    fun sensitiveCommandsAreRisky() {
        val risky = listOf(
            "rm -r old_dir",
            "chmod +x run.sh",
            "chown user file.txt",
            "npm run deploy",
            "git push --force",
        )
        for (cmd in risky) {
            assertEquals(cmd, CommandRisk.RISKY, CommandRiskClassifier.classify(cmd))
        }
    }

    @Test
    fun normalCommandsAreSafe() {
        val safe = listOf(
            "ls -la",
            "echo hi",
            "rm notes.txt",
            "cat super_report.txt",
            "git status",
            "ls /",
        )
        for (cmd in safe) {
            assertEquals(cmd, CommandRisk.SAFE, CommandRiskClassifier.classify(cmd))
        }
    }
}
