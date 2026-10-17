if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
if redis.call('HGET', KEYS[1], 'online') ~= '1'
   or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1]
   or redis.call('HGET', KEYS[1], 'region') ~= ARGV[2] then return -2 end
local sequence = tonumber(ARGV[3])
if not sequence or sequence < 0 or (sequence == 0 and ARGV[6] ~= 'RESTORE') then return -3 end
local latitude = tonumber(ARGV[4])
local longitude = tonumber(ARGV[5])
if not latitude or not longitude or latitude < -90 or latitude > 90
   or longitude < -180 or longitude > 180 then return -3 end
local current = tonumber(redis.call('HGET', KEYS[1], 'sequence') or '0')
if sequence < current then return -4 end
if sequence == current then
    if ARGV[6] == 'RESTORE' then
        redis.call('HSET', KEYS[1], 'latitude', ARGV[4], 'longitude', ARGV[5], 'heartbeatMs', ARGV[7])
        redis.call('EXPIRE', KEYS[1], 120)
        return 2
    end
    if redis.call('HGET', KEYS[1], 'latitude') == ARGV[4]
       and redis.call('HGET', KEYS[1], 'longitude') == ARGV[5] then return 2 end
    return -4
end
local now
if ARGV[6] == 'RESTORE' then
    now = tonumber(ARGV[7])
    if not now then return -3 end
else
    local time = redis.call('TIME')
    now = time[1] * 1000 + math.floor(time[2] / 1000)
end
redis.call('HSET', KEYS[1], 'sequence', sequence, 'latitude', ARGV[4],
    'longitude', ARGV[5], 'heartbeatMs', now)
redis.call('EXPIRE', KEYS[1], 120)
return 1
