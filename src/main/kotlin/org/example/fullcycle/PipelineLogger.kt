package org.example.fullcycle

class PipelineLogger {
    
    fun info(message: String) {
        println("${PipelineEmoji.INFO} $message")
    }
    
    fun error(message: String) {
        println("${PipelineEmoji.ERROR} $message")
    }
    
    fun warning(message: String) {
        println("${PipelineEmoji.WARNING} $message")
    }
    
    fun success(message: String) {
        println("${PipelineEmoji.SUCCESS} $message")
    }
    
    fun analyzing(message: String) {
        println("${PipelineEmoji.ANALYZING} $message")
    }
    
    fun planning(message: String) {
        println("${PipelineEmoji.PLAN} $message")
    }
    
    fun changes(message: String) {
        println("${PipelineEmoji.CHANGES} $message")
    }
    
    fun branch(message: String) {
        println("${PipelineEmoji.BRANCH} $message")
    }
    
    fun commit(message: String) {
        println("${PipelineEmoji.COMMIT} $message")
    }
    
    fun push(message: String) {
        println("${PipelineEmoji.PUSH} $message")
    }
    
    fun pullRequest(message: String) {
        println("${PipelineEmoji.PR} $message")
    }
    
    fun review(message: String) {
        println("${PipelineEmoji.REVIEW} $message")
    }
    
    fun ci(message: String) {
        println("${PipelineEmoji.CI} $message")
    }
    
    fun merge(message: String) {
        println("${PipelineEmoji.MERGE} $message")
    }
    
    fun loading(message: String) {
        println("${PipelineEmoji.LOADING} $message")
    }
    
    fun approved(message: String) {
        println("${PipelineEmoji.APPROVED} $message")
    }
    
    fun rejected(message: String) {
        println("${PipelineEmoji.REJECTED} $message")
    }
    
    fun pending(message: String) {
        println("${PipelineEmoji.PENDING} $message")
    }
    
    fun running(message: String) {
        println("${PipelineEmoji.RUNNING} $message")
    }
    
    fun completed(message: String) {
        println("${PipelineEmoji.COMPLETED} $message")
    }
    
    fun cancelled(message: String) {
        println("${PipelineEmoji.CANCELLED} $message")
    }
    
    fun search(message: String) {
        println("${PipelineEmoji.SEARCH} $message")
    }
    
    fun rag(message: String) {
        println("${PipelineEmoji.RAG} $message")
    }
    
    fun fix(message: String) {
        println("${PipelineEmoji.FIX} $message")
    }
    
    fun iterate(message: String) {
        println("${PipelineEmoji.ITERATE} $message")
    }
}