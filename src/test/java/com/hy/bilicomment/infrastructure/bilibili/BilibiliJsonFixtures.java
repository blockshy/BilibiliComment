package com.hy.bilicomment.infrastructure.bilibili;

final class BilibiliJsonFixtures {

    static final String VIDEO_VIEW = """
            {
              "code": 0,
              "data": {
                "aid": 987654321,
                "title": "Fixture 视频"
              }
            }
            """;

    static final String DYNAMIC_DETAIL = """
            {
              "code": 0,
              "data": {
                "item": {
                  "basic": {
                    "comment_type": "11",
                    "comment_id_str": "7654321"
                  },
                  "modules": {
                    "module_dynamic": {
                      "major": {
                        "archive": {
                          "title": "Fixture 动态标题"
                        }
                      }
                    },
                    "module_author": {
                      "name": "Fixture UP"
                    }
                  }
                }
              }
            }
            """;

    static final String DYNAMIC_TYPE_17_DETAIL = """
            {
              "code": 0,
              "data": {
                "item": {
                  "basic": {
                    "comment_type": "17",
                    "comment_id_str": ""
                  },
                  "modules": {
                    "module_author": {
                      "name": "Fixture UP"
                    }
                  }
                }
              }
            }
            """;

    static final String CREATOR_FIRST_PAGE = """
            {
              "code": 0,
              "data": {
                "items": [
                  {
                    "type": "DYNAMIC_TYPE_AV",
                    "modules": {
                      "module_dynamic": {
                        "major": {
                          "archive": {
                            "bvid": "BV1FixtureVideo",
                            "aid": "10001",
                            "title": "Fixture 投稿"
                          }
                        }
                      }
                    }
                  },
                  {
                    "type": "DYNAMIC_TYPE_FORWARD",
                    "id_str": "20001",
                    "basic": {"comment_id_str": "30001", "comment_type": "17"}
                  },
                  {
                    "type": "DYNAMIC_TYPE_WORD",
                    "id_str": "20002",
                    "basic": {"comment_id_str": "30002", "comment_type": "17"}
                  },
                  {
                    "type": "DYNAMIC_TYPE_DRAW",
                    "id_str": "20003",
                    "basic": {"comment_id_str": "30003", "comment_type": "11"}
                  },
                  {
                    "type": "DYNAMIC_TYPE_ARTICLE",
                    "id_str": "20004",
                    "basic": {"comment_id_str": "30004", "comment_type": "12"}
                  },
                  {
                    "type": "DYNAMIC_TYPE_LIVE_RCMD",
                    "id_str": "ignored-unknown",
                    "basic": {"comment_id_str": "ignored", "comment_type": "1"}
                  }
                ]
              }
            }
            """;

    static final String NAV_WBI_KEYS = """
            {
              "code": 0,
              "data": {
                "isLogin": true,
                "wbi_img": {
                  "img_url": "https://fixture.invalid/7cd084941338484aae1ad9425b84077c.png",
                  "sub_url": "https://fixture.invalid/4932caff0ff746eab6f01bf08b70ac45.png"
                }
              }
            }
            """;

    static final String COMMENT_PAGE = """
            {
              "code": 0,
              "data": {
                "replies": [
                  {
                    "rpid": 1001,
                    "parent_str": "0",
                    "ctime": 1700000000,
                    "member": {
                      "mid": "fixture-mid-1",
                      "uname": "Fixture 用户",
                      "avatar": "https://fixture.invalid/avatar.png",
                      "level_info": {"current_level": 6}
                    },
                    "content": {"message": "顶层评论"},
                    "replies": [
                      {
                        "rpid": 1002,
                        "parent_str": "1001",
                        "ctime": 1700000001,
                        "member": {
                          "mid": "fixture-mid-2",
                          "uname": "Fixture 回复者",
                          "avatar": "",
                          "level_info": {"current_level": 3}
                        },
                        "content": {"message": "一层回复"},
                        "replies": [
                          {
                            "rpid": 1003,
                            "parent_str": "1002",
                            "ctime": 1700000002,
                            "member": {"mid": "must-not-be-read"},
                            "content": {"message": "嵌套二层回复"}
                          }
                        ]
                      },
                      {
                        "rpid": 0,
                        "ctime": 1700000003,
                        "member": {"mid": "invalid-child"},
                        "content": {"message": "无效回复"}
                      }
                    ]
                  },
                  {
                    "rpid": 1004,
                    "ctime": 1700000004,
                    "member": {"mid": ""},
                    "content": {"message": "缺少 mid 的无效评论"}
                  }
                ],
                "cursor": {
                  "is_end": false,
                  "pagination_reply": {"next_offset": "fixture-next-cursor"}
                }
              }
            }
            """;

    static final String API_ERROR = """
            {"code": -101, "message": "Fixture 凭据未登录", "data": null}
            """;

    static final String DATA_MISSING = """
            {"code": 0}
            """;

    private BilibiliJsonFixtures() {}
}
