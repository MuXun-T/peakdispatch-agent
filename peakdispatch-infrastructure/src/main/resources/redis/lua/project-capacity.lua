local current = tonumber(redis.call('HGET', KEYS[1], 'version') or '-1')
local incoming = tonumber(ARGV[1])
if incoming >= current then
    redis.call('HSET', KEYS[1], 'version', incoming, 'committed', ARGV[2],
        'reserved', ARGV[3], 'limit', ARGV[4], 'ready', '1')
end
if ARGV[5] ~= '' then
    redis.call('HDEL', KEYS[2], ARGV[5])
    redis.call('ZREM', KEYS[3], ARGV[5])
end
return current > incoming and 0 or 1
