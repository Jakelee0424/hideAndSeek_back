package com.game3d.server.dto;

/** 3D 좌표/벡터. 규약: y-up, 미터 단위. 프론트 net/types.ts의 Vec3와 일치. */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);
}
