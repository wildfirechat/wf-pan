package com.wildfirechat.pan.dto.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserSpacesVO {
    
    private SpaceVO publicSpace;
    private SpaceVO privateSpace;
}
