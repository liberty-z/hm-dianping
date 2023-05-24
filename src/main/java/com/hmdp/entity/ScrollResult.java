package com.hmdp.entity;

import lombok.Data;

import java.util.List;

/**
 * @author ZhaiLibo
 * @date 2023/5/24 -15:37
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
