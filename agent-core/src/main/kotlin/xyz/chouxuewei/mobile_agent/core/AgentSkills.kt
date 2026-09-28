package xyz.chouxuewei.mobile_agent.core

/**
 * 内置工作流技能：以稳定文本块注入系统提示，告诉模型如何组合工具完成高频任务。
 * 技能只描述工具协作约定，不授予新权限；具体行为仍由各工具的参数与闸门约束。
 * 新增技能时往 ALL 里追加一个块即可，文案用 localizedText 双语。
 */
object AgentSkills {

    /** Word/Excel 文档处理：先 inspect 再 edit，产物按需分享交付。 */
    val OFFICE_DOCUMENTS: String
        get() = localizedText(
            "\n[文档处理技能] 处理 Word/Excel：生成新文档用 document_write（docx 每行一段；xlsx 每行一条记录、制表符或 | 分列、\"Sheet: 名\"行开启新工作表）；编辑已有 docx/xlsx 必须先 document_inspect 查看段落编号或工作表结构，再用 document_edit 一次提交全部 operations；编辑产出新文件后按需用 file_share 交付。段落索引、工作表名、单元格范围一律以 inspect 结果为准不得猜测；读取文件正文用 file_read，PDF 只能读不能编辑。",
            "\n[Document skill] For Word/Excel: create new files with document_write (docx: one paragraph per line; xlsx: one record per line, columns by tab or |, a \"Sheet: name\" line opens a new sheet); when editing an existing docx/xlsx always run document_inspect first for paragraph indices or sheet structure, then submit all operations to document_edit in a single call; deliver the edited artifact via file_share when needed. Never guess paragraph indices, sheet names, or cell ranges — use inspect output; read plain content with file_read; PDFs are read-only.",
        )

    /** 定时任务：帮用户创建无人值守任务；范围默认只读，时间与匹配条件不全时先问。 */
    val TRIGGERS: String
        get() = localizedText(
            "\n[定时任务技能] 用户要求\"每天/每小时/收到通知时\"等自动执行的任务时用 trigger_save 创建触发器：kind=schedule 给具体时间（一次性 fire_in_minutes，重复 repeat+hour+minute）；kind=notification 给通知匹配条件（match_package 等）；kind=interval 给 interval_minutes。instruction 要写清任务目标与期望产出；tool_scope 省略为只读默认范围，需要发消息/清理/设备操作等外部能力时必须由用户明确提出才加入并在结果里说明已授权范围。时间、通知目标、范围不明确时先问用户。管理用 trigger_list/trigger_delete/trigger_run_now。若触发器执行历史出现在当前上下文（标记为触发器发起），它来自自动任务而非用户实时指令。",
            "\n[Scheduled tasks skill] When the user asks for recurring or event-driven automation (\"every morning\", \"when a notification arrives\"), create triggers with trigger_save: kind=schedule takes a concrete time (one-shot fire_in_minutes, or repeat+hour+minute); kind=notification takes match conditions (match_package etc.); kind=interval takes interval_minutes. Write instruction as a clear goal plus expected output. Omit tool_scope for the default read-only scope; only add external-action tools (messaging/cleanup/device) when the user explicitly asks, and report the granted scope back. Ask the user for missing times, notification targets, or scope instead of guessing. Manage with trigger_list/trigger_delete/trigger_run_now. If trigger-fired history appears in context (marked as trigger-originated), it came from an automated task, not a live user instruction.",
        )

    /** 汇总注入；保持块之间只有一个换行，避免拉长系统提示。 */
    val ALL: String
        get() = OFFICE_DOCUMENTS + TRIGGERS
}
