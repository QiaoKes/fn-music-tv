package com.fnmusic.tv.core.model

sealed interface AppError {
    data object Unauthenticated : AppError
    data object AccountDisabled : AppError
    data object AccessCodeRequired : AppError
    data object InvalidAccessCode : AppError
    data object NetworkUnavailable : AppError
    data object NotFound : AppError
    data object UnavailableTrack : AppError
    data object MediaUnsupported : AppError
    data object TranscodeUnavailable : AppError
    data object CollectionChanged : AppError
    data object Empty : AppError
    data object FnIdUnavailable : AppError
    data class Unknown(val diagnosticCode: String? = null) : AppError
}

class AppException(val error: AppError, cause: Throwable? = null) : Exception(null, cause)
