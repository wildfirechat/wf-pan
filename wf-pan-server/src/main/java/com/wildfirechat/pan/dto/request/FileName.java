package com.wildfirechat.pan.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 文件/文件夹名称：非空、不超过 255 个字符（与 pan_file.name 一致）、不含路径分隔符和控制字符
 */
@NotBlank(message = "名称不能为空")
@Size(max = 255, message = "名称不能超过255个字符")
@Pattern(regexp = "[^/\\\\\\p{Cntrl}]+", message = "名称不能包含 / \\ 或控制字符")
@Constraint(validatedBy = {})
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FileName {

    String message() default "名称不合法";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
