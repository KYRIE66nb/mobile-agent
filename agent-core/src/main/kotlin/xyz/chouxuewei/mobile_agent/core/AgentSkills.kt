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

    /** 汇总注入；保持块之间只有一个换行，避免拉长系统提示。 */
    val ALL: String
        get() = OFFICE_DOCUMENTS
}
