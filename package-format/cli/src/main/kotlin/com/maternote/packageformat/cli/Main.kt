package com.maternote.packageformat.cli

import com.maternote.packageformat.validator.PackageValidator
import java.io.File

fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: maternote-package <validate|inspect|diff> <file> [other]" }
    val validator = PackageValidator()
    when (args[0]) {
        "validate" -> { val (_, report) = validator.validatePackage(File(args[1])); report.errors.forEach { println("ERROR ${it.code} ${it.path}: ${it.detail}") }; report.warnings.forEach { println("WARN ${it.code} ${it.path}: ${it.detail}") }; if (!report.isValid) error("package invalid"); println("OK assets=${report.assetCount} pages=${report.pageCount} activities=${report.activityCount}") }
        "inspect" -> { val (m, report) = validator.validatePackage(File(args[1])); requireNotNull(m); println("${m.book.title}\nbookId=${m.book.bookId}\nrevisionId=${m.book.revisionId}\npages=${report.pageCount}\nactivities=${report.activityCount}\nassets=${report.assetCount}") }
        "diff" -> { require(args.size == 3); val (a, _) = validator.validatePackage(File(args[1])); val (b, _) = validator.validatePackage(File(args[2])); requireNotNull(a); requireNotNull(b); println("pages +${b.pages.map { it.pageId }.toSet() - a.pages.map { it.pageId }.toSet()} -${a.pages.map { it.pageId }.toSet() - b.pages.map { it.pageId }.toSet()}"); println("activities +${b.activities.map { it.activityId }.toSet() - a.activities.map { it.activityId }.toSet()} -${a.activities.map { it.activityId }.toSet() - b.activities.map { it.activityId }.toSet()}") }
        else -> error("unknown command ${args[0]}")
    }
}
