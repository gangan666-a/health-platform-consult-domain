package com.example.mall.orderservice.web;

import com.example.mall.common.exception.BusinessException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * 网页错误处理：业务异常渲染到 error.html。
 * 仅作用于 web 包，避免与 api 包的 JSON 错误处理冲突。
 */
@ControllerAdvice(basePackages = "com.example.mall.orderservice.web")
public class GlobalExceptionHandler {

    /** 业务异常：把提示信息展示到错误页 */
    @ExceptionHandler(BusinessException.class)
    public ModelAndView handleBusiness(BusinessException ex) {
        ModelAndView mv = new ModelAndView("error");
        mv.addObject("message", ex.getMessage());
        return mv;
    }

    /** 兜底异常：仅给用户安全通用文案，不回显异常内部信息（防内部信息泄露，评审 P2-04） */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleOther(Exception ex) {
        ModelAndView mv = new ModelAndView("error");
        mv.addObject("message", "系统繁忙，请稍后重试");
        return mv;
    }
}
